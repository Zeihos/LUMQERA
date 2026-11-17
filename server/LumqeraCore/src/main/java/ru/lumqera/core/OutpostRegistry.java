package ru.lumqera.core;

import java.util.*;

/** Control point model. Capture requires uninterrupted uncontested presence; rewards only to present owners. */
final class OutpostRegistry {
    enum OutcomeType { NONE, CAPTURED, REWARD }
    record Outcome(OutcomeType type, String clan, String point) {
        static Outcome none() { return new Outcome(OutcomeType.NONE,null,null); }
    }
    static final class Point {
        final String name;
        final UUID world;
        final int x,y,z;
        final int radius;
        String owner;
        String candidate;
        long candidateSince;
        long rewardedAt;
        long holdingSince; // volatile: presence must be continuous, even after a reboot
        Point(String name,UUID world,int x,int y,int z,int radius) {
            this.name=name; this.world=world; this.x=x; this.y=y; this.z=z; this.radius=radius;
        }
    }
    private final Map<String,Point> points=new TreeMap<>();
    static boolean validName(String name) { return name!=null && name.matches("[A-Za-z0-9_]{3,24}"); }
    static String key(String name) { return name==null?"":name.toLowerCase(Locale.ROOT); }
    Point get(String name) { return points.get(key(name)); }
    Collection<Point> all() { return Collections.unmodifiableCollection(points.values()); }
    boolean set(String name,UUID world,int x,int y,int z,int radius) {
        if (!validName(name)||world==null||radius<5||radius>64||points.containsKey(key(name))) return false;
        points.put(key(name),new Point(name,world,x,y,z,radius)); return true;
    }
    boolean remove(String name) { return points.remove(key(name))!=null; }
    boolean restore(String name,UUID world,int x,int y,int z,int radius,String owner,long rewardedAt) {
        if (!set(name,world,x,y,z,radius)) return false;
        Point p=get(name); p.owner=ClanRegistry.validName(owner)?ClanRegistry.key(owner):null;
        p.rewardedAt=Math.max(0,rewardedAt); return true;
    }
    void eraseClan(String clan) {
        String id=key(clan);
        for (Point p:points.values()) {
            if (id.equals(p.owner)) { p.owner=null; p.rewardedAt=0; p.holdingSince=0; }
            if (id.equals(p.candidate)) { p.candidate=null; p.candidateSince=0; }
        }
    }
    /** occupants are clans counted in range; a contested point or insufficient population stalls the capture. */
    Outcome update(String name,Map<String,Integer> occupants,long now,int minMembers,long captureMs,long rewardMs) {
        Point p=get(name);
        if (p==null||now<=0||captureMs<1||rewardMs<1) return Outcome.none();
        Map<String,Integer> valid=new HashMap<>();
        for (var e:occupants.entrySet()) {
            if (ClanRegistry.validName(e.getKey()) && e.getValue()!=null && e.getValue()>0)
                valid.merge(key(e.getKey()),e.getValue(),Integer::sum);
        }
        if (valid.size()!=1) { p.candidate=null; p.candidateSince=0; p.holdingSince=0; return Outcome.none(); }
        var only=valid.entrySet().iterator().next();
        if (only.getValue()<Math.max(1,minMembers)) { p.candidate=null; p.candidateSince=0; p.holdingSince=0; return Outcome.none(); }
        String clan=only.getKey();
        if (clan.equals(p.owner)) {
            p.candidate=null; p.candidateSince=0;
            if (p.holdingSince<=0 || now<p.holdingSince) p.holdingSince=now;
            // Full interval of uninterrupted presence required; no payout for offline time.
            if (now>=p.rewardedAt && now-p.rewardedAt>=rewardMs && now-p.holdingSince>=rewardMs) {
                p.rewardedAt=now;
                p.holdingSince=now;
                return new Outcome(OutcomeType.REWARD,clan,p.name);
            }
            return Outcome.none();
        }
        p.holdingSince=0;
        if (!clan.equals(p.candidate) || now < p.candidateSince) {
            p.candidate=clan; p.candidateSince=now; return Outcome.none();
        }
        if (now-p.candidateSince>=captureMs) {
            p.owner=clan; p.candidate=null; p.candidateSince=0; p.rewardedAt=now; p.holdingSince=now;
            return new Outcome(OutcomeType.CAPTURED,clan,p.name);
        }
        return Outcome.none();
    }
}
