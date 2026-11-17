package ru.lumqera.core;

import java.util.*;

/** Deterministic war engine. Main-thread-only, independent of Bukkit. */
final class WarRegistry {
    enum Result { OK, INVALID, SELF, NOT_FOUND, PENDING, ALREADY_BUSY, EXPIRED, NOT_AUTHORIZED }
    record Challenge(String attacker, String defender, long expiresAt) {}
    record Finish(String winner, String loser, boolean draw, String reason) {}
    static final class War {
        final String a, b;
        final long endsAt;
        final int goal;
        int aScore, bScore;
        War(String a, String b, long endsAt, int goal, int aScore, int bScore) {
            this.a = a; this.b = b; this.endsAt = endsAt; this.goal = goal;
            this.aScore = aScore; this.bScore = bScore;
        }
        boolean includes(String clan) { return a.equals(key(clan)) || b.equals(key(clan)); }
        String other(String clan) { return a.equals(key(clan)) ? b : a; }
        int score(String clan) { return a.equals(key(clan)) ? aScore : bScore; }
    }
    record Hit(boolean counted, Finish ended) {}
    private final Map<String, Challenge> challenges = new HashMap<>();
    private final List<War> active = new ArrayList<>();
    private final Map<String,Integer> wins = new HashMap<>();
    private final Map<String,Integer> losses = new HashMap<>();
    private final Map<String,Integer> draws = new HashMap<>();
    private static String key(String clan) { return clan == null ? "" : ClanRegistry.key(clan); }
    private static boolean valid(String clan) { return ClanRegistry.validName(clan); }
    Collection<War> active() { return Collections.unmodifiableCollection(active); }
    Collection<Challenge> challenges() { return Collections.unmodifiableCollection(challenges.values()); }
    Map<String,Integer> wins() { return Collections.unmodifiableMap(wins); }
    Map<String,Integer> losses() { return Collections.unmodifiableMap(losses); }
    Map<String,Integer> draws() { return Collections.unmodifiableMap(draws); }
    int wins(String name) { return wins.getOrDefault(key(name),0); }
    int losses(String name) { return losses.getOrDefault(key(name),0); }
    int draws(String name) { return draws.getOrDefault(key(name),0); }
    War war(String clan) { return active.stream().filter(w -> w.includes(clan)).findFirst().orElse(null); }
    Challenge challengeFor(String defender) { return challenges.get(key(defender)); }
    Result challenge(String from, String to, long now, long ttlMs) {
        if (!valid(from) || !valid(to) || ttlMs <= 0 || now < 0) return Result.INVALID;
        String attacker=key(from), defender=key(to);
        if (attacker.equals(defender)) return Result.SELF;
        clearExpiredChallenges(now);
        if (war(attacker) != null || war(defender) != null || challenges.containsKey(defender)
                || challenges.containsKey(attacker) || challenges.values().stream().anyMatch(c -> c.attacker.equals(attacker) || c.attacker.equals(defender)))
            return Result.ALREADY_BUSY;
        challenges.put(defender, new Challenge(attacker,defender,saturate(now,ttlMs)));
        return Result.OK;
    }
    Result decline(String to, String from, long now) {
        Challenge c=challengeFor(to);
        if (c==null || !c.attacker.equals(key(from))) return Result.NOT_FOUND;
        challenges.remove(key(to));
        return c.expiresAt <= now ? Result.EXPIRED : Result.OK;
    }
    Result accept(String to, String from, long now, long durationMs, int goal) {
        if (durationMs <= 0 || goal < 1) return Result.INVALID;
        Challenge c=challengeFor(to);
        if (c==null || !c.attacker.equals(key(from))) return Result.NOT_FOUND;
        challenges.remove(key(to));
        if (c.expiresAt <= now) return Result.EXPIRED;
        if (war(to)!=null || war(from)!=null) return Result.ALREADY_BUSY;
        active.add(new War(c.attacker,c.defender,saturate(now,durationMs),goal,0,0));
        challenges.values().removeIf(v -> v.attacker.equals(c.attacker) || v.defender.equals(c.attacker)
                || v.attacker.equals(c.defender) || v.defender.equals(c.defender));
        return Result.OK;
    }
    Hit creditedKill(String killer, String victim, long now) {
        War w=war(killer);
        if (w==null || victim==null || !w.other(killer).equals(key(victim)) || now >= w.endsAt)
            return new Hit(false,null);
        if (w.a.equals(key(killer))) w.aScore++; else w.bScore++;
        Finish finish = Math.max(w.aScore,w.bScore) >= w.goal ? finish(w,"score") : null;
        return new Hit(true,finish);
    }
    Finish surrender(String clan) {
        War w=war(clan);
        return w==null ? null : finishWithWinner(w,w.other(clan),"surrender");
    }
    Finish abandon(String clan) { // dissolution forfeits current war; no record for deleted clan
        return surrender(clan);
    }
    List<Finish> tick(long now) {
        clearExpiredChallenges(now);
        List<Finish> ended=new ArrayList<>();
        for (War w : List.copyOf(active)) if (now>=w.endsAt) ended.add(finish(w,"time"));
        return ended;
    }
    private Finish finish(War w, String reason) {
        if (w.aScore == w.bScore) {
            active.remove(w);
            draws.merge(w.a,1,WarRegistry::inc); draws.merge(w.b,1,WarRegistry::inc);
            return new Finish(null,null,true,reason);
        }
        return finishWithWinner(w, w.aScore>w.bScore?w.a:w.b,reason);
    }
    private Finish finishWithWinner(War w,String winner,String reason) {
        if (!active.remove(w)) throw new IllegalStateException("War already completed");
        String loser=w.other(winner);
        wins.merge(winner,1,WarRegistry::inc); losses.merge(loser,1,WarRegistry::inc);
        return new Finish(winner,loser,false,reason);
    }
    boolean restoreWar(String a,String b,long endsAt,int goal,int aScore,int bScore) {
        if (!valid(a)||!valid(b)||key(a).equals(key(b))||endsAt<=0||goal<1||goal>1000
                ||aScore<0||bScore<0||aScore>=goal||bScore>=goal||war(a)!=null||war(b)!=null) return false;
        active.add(new War(key(a),key(b),endsAt,goal,aScore,bScore));
        return true;
    }
    boolean restoreRecord(String clan,int won,int lost,int tied) {
        if (!valid(clan) || won < 0 || lost < 0 || tied < 0 || wins.containsKey(key(clan))) return false;
        wins.put(key(clan),won); losses.put(key(clan),lost); draws.put(key(clan),tied);
        return true;
    }
    void eraseClan(String clan) {
        challenges.values().removeIf(c -> c.attacker.equals(key(clan)) || c.defender.equals(key(clan)));
        wins.remove(key(clan)); losses.remove(key(clan)); draws.remove(key(clan));
    }
    private void clearExpiredChallenges(long now) { challenges.values().removeIf(c -> now >= c.expiresAt); }
    private static int inc(int x,int y) { return (int)Math.min(Integer.MAX_VALUE,(long)x+y); }
    private static long saturate(long x,long delta) { return delta>Long.MAX_VALUE-x?Long.MAX_VALUE:x+delta; }
}
