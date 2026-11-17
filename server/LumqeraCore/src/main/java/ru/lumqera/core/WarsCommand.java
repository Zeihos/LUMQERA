package ru.lumqera.core;

import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import java.util.*;

/** Leaders negotiate clan wars; ranked PvP kills, not free-form commands, determine scores. */
final class WarsCommand implements CommandExecutor, TabCompleter {
    private static final String PREFIX="§5§lLUMQERA §8» §r";
    private final ClansService clans;
    private final WarService wars;
    private final long inviteMs,durationMs;
    private final int goal;
    WarsCommand(ClansService clans,WarService wars,long inviteMs,long durationMs,int goal) {
        this.clans=clans;this.wars=wars;this.inviteMs=inviteMs;this.durationMs=durationMs;this.goal=goal;
    }
    private static void tell(CommandSender s,String line) { s.sendMessage(PREFIX+line); }
    @Override public boolean onCommand(CommandSender sender,Command cmd,String label,String[] args) {
        if (args.length==0||args[0].equalsIgnoreCase("help")) {
            tell(sender,"§d/war challenge <клан> §7— вызов на войну (глава)");
            tell(sender,"§d/war accept <клан> §7— принять вызов (глава)");
            tell(sender,"§d/war decline <клан> §7— отклонить вызов (глава)");
            tell(sender,"§d/war surrender confirm §7— признать поражение (глава)");
            tell(sender,"§d/war info [клан] §7— текущая война и счёт");
            tell(sender,"§d/war top §7— топ побед");
            return true;
        }
        String action=args[0].toLowerCase(Locale.ROOT);
        if (action.equals("top")) {
            List<ClanRegistry.Clan> sorted=new ArrayList<>(clans.all());
            sorted.sort(Comparator.comparingInt((ClanRegistry.Clan c)->wars.state().wins(c.name)).reversed().thenComparing(c->c.name));
            if (sorted.isEmpty()) tell(sender,"§7Кланов пока нет.");
            for(int i=0;i<Math.min(10,sorted.size());i++) {
                ClanRegistry.Clan c=sorted.get(i);
                tell(sender,"§d#"+(i+1)+" §f"+c.name+" §7Побед: §a"+wars.state().wins(c.name)+" §7Поражений: §c"+wars.state().losses(c.name));
            }
            return true;
        }
        if (action.equals("info")&&args.length==2) { show(sender,args[1]); return true; }
        if (!(sender instanceof Player player)) { tell(sender,"§cТолько в игре, кроме /war top и /war info <клан>."); return true; }
        ClanRegistry.Clan clan=clans.of(player.getUniqueId());
        if (clan==null) { tell(player,"§cТы не состоишь в клане."); return true; }
        if (action.equals("info")) { show(sender,clan.name); return true; }
        if (!clan.owner.equals(player.getUniqueId())) { tell(sender,"§cОбъявлять и завершать войну может только глава клана.");return true; }
        long now=System.currentTimeMillis();
        if ((action.equals("challenge")||action.equals("accept")||action.equals("decline"))&&args.length==2) {
            ClanRegistry.Clan other=clans.byName(args[1]);
            if (other==null) { tell(sender,"§cКлан не найден.");return true; }
            WarRegistry.Result result=switch(action) {
                case "challenge" -> wars.state().challenge(clan.name,other.name,now,inviteMs);
                case "accept" -> wars.state().accept(clan.name,other.name,now,durationMs,goal);
                default -> wars.state().decline(clan.name,other.name,now);
            };
            if (result==WarRegistry.Result.OK) {
                if (action.equals("accept")) wars.save();
                if (action.equals("accept")) Bukkit.broadcastMessage(PREFIX+"§dВойна кланов: §f"+clan.name+" §dпротив §f"+other.name+"§d! Цель — §f"+goal+"§d убийств.");
                else if (action.equals("challenge")) {
                    tell(sender,"§aВызов отправлен клану §f"+other.name+"§a. Срок — "+inviteMs/1000+" сек.");
                    Player leader=Bukkit.getPlayer(other.owner);
                    if (leader!=null) tell(leader,"§eКлан §f"+clan.name+" §eвызывает вас на войну. §d/war accept "+clan.name);
                } else tell(sender,"§eВызов отклонён.");
            } else tell(sender,"§cНе удалось выполнить действие: §f"+result);
            return true;
        }
        if (action.equals("surrender") && args.length==2 && args[1].equalsIgnoreCase("confirm")) {
            WarRegistry.Finish finish=wars.state().surrender(clan.name);
            if (finish==null) tell(sender,"§cТвой клан сейчас не участвует в войне.");
            else { wars.save(); Bukkit.broadcastMessage(PREFIX+"§dКлан §f"+clan.name+" §dсдался. Победитель: §a"+finish.winner()); }
            return true;
        }
        tell(sender,"§cИспользование: /war help"); return true;
    }
    private void show(CommandSender sender,String name) {
        ClanRegistry.Clan clan=clans.byName(name);
        if (clan==null) { tell(sender,"§cКлан не найден."); return; }
        WarRegistry.War w=wars.state().war(clan.name);
        tell(sender,"§d"+clan.name+" §7| Побед: §a"+wars.state().wins(clan.name)+" §7Поражений: §c"+wars.state().losses(clan.name)+" §7Ничьих: §f"+wars.state().draws(clan.name));
        if (w==null) {
            WarRegistry.Challenge c=wars.state().challengeFor(clan.name);
            tell(sender,c==null?"§7Сейчас нет активной войны.":"§eОжидается ответ на вызов от §f"+c.attacker());
        } else tell(sender,"§dВойна: §f"+w.a+" §7"+w.aScore+" : "+w.bScore+" §f"+w.b+" §7| До: §f"+new Date(w.endsAt));
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args) {
        if(args.length==1)return List.of("help","info","top","challenge","accept","decline","surrender").stream().filter(s->s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if(args.length==2&&List.of("challenge","accept","decline","info").contains(args[0].toLowerCase(Locale.ROOT)))
            return clans.all().stream().map(c->c.name).filter(s->s.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if(args.length==2&&args[0].equalsIgnoreCase("surrender"))return List.of("confirm");
        return List.of();
    }
}
