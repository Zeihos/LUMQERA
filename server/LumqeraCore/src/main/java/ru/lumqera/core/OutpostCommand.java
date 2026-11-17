package ru.lumqera.core;

import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import java.util.*;

final class OutpostCommand implements CommandExecutor,TabCompleter {
    private static final String PREFIX="§5§lLUMQERA §8» §r";
    private final OutpostService outposts;
    private final List<String> allowedWorlds;
    private final int radius;
    OutpostCommand(OutpostService service,List<String> worlds,int radius) {
        outposts=service;allowedWorlds=worlds;this.radius=radius;
    }
    private static void tell(CommandSender sender,String msg) { sender.sendMessage(PREFIX+msg); }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(args.length==0 || args[0].equalsIgnoreCase("list")) {
            if(outposts.state().all().isEmpty()) tell(sender,"§7Контрольных точек пока нет.");
            else for (OutpostRegistry.Point p:outposts.state().all()) tell(sender,"§d"+p.name+" §7| Владелец: §f"+(p.owner==null?"нет":p.owner)+" §7| X="+p.x+" Z="+p.z);
            return true;
        }
        if(args[0].equalsIgnoreCase("info")&&args.length==2) {
            OutpostRegistry.Point p=outposts.state().get(args[1]);
            if (p==null) tell(sender,"§cТочка не найдена.");
            else tell(sender,"§d"+p.name+" §7| Владелец: §f"+(p.owner==null?"нет":p.owner)+" §7| Радиус: §f"+p.radius+" §7| Мир: §f"+p.world);
            return true;
        }
        if(!sender.hasPermission("lumqera.admin")) { tell(sender,"§cНедостаточно прав."); return true; }
        if(args[0].equalsIgnoreCase("set")&&args.length==2) {
            if(!(sender instanceof Player p)) { tell(sender,"§cТочку устанавливают в игре."); return true; }
            if(!outposts.create(args[1],p.getLocation(),radius,allowedWorlds)) tell(sender,"§cНе удалось создать точку: имя занято, нет доступа к миру либо территория защищена.");
            else { tell(sender,"§aКонтрольная точка §f"+args[1]+"§a создана."); }
            return true;
        }
        if(args[0].equalsIgnoreCase("remove")&&args.length==2) {
            if(outposts.state().remove(args[1])) {outposts.save();tell(sender,"§aТочка удалена.");}
            else tell(sender,"§cТочка не найдена.");
            return true;
        }
        tell(sender,"§cИспользование: /outpost list|info <имя>|set <имя>|remove <имя>"); return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args) {
        if(args.length==1) return (sender.hasPermission("lumqera.admin")?List.of("list","info","set","remove"):List.of("list","info")).stream().filter(x->x.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if(args.length==2 && List.of("info","remove").contains(args[0].toLowerCase(Locale.ROOT))) return outposts.state().all().stream().map(p->p.name).filter(x->x.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        return List.of();
    }
}
