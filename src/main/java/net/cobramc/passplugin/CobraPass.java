package net.cobramc.passplugin;

import java.io.File;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.logging.Level;
import org.bukkit.*;
import org.bukkit.block.data.Ageable;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;

@SuppressWarnings("deprecation")
public final class CobraPass extends JavaPlugin implements Listener, CommandExecutor {
    private Connection db;
    private int season;
    private long start;
    private YamlConfiguration rewards, questsFile, shop;
    private final Map<UUID, User> cache = new HashMap<>();
    private final Map<String, Long> pvpCooldowns = new HashMap<>();
    private final List<Quest> daily = new ArrayList<>();
    private final List<Quest> weekly = new ArrayList<>();

    static final class User {
        UUID id;
        int xp, coins;
        boolean premium;
        long lastJoin, lastActive;
        double walkRemainder;
        Map<String,Integer> progress = new HashMap<>();
        Set<String> completed = new HashSet<>(), claimed = new HashSet<>();
        User(UUID id) {this.id=id; lastActive=System.currentTimeMillis();}
    }
    record Quest(String id,String name,String type,int goal,int xp,int week,Material icon) {}
    static final class Page implements InventoryHolder {
        Inventory inventory;
        final String kind;
        final int number;
        Page(String k,int n){kind=k;number=n;}
        public Inventory getInventory(){return inventory;}
    }

    @Override public void onEnable() {
        try {
            saveDefaultConfig();
            for (String name : List.of("rewards.yml","quests.yml","shop.yml")) {
                if (!new File(getDataFolder(),name).exists()) saveResource(name,false);
            }
            loadFiles();
            Class.forName("org.sqlite.JDBC");
            db=DriverManager.getConnection("jdbc:sqlite:"+new File(getDataFolder(),"players.db").getAbsolutePath());
            try (Statement st=db.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY,v TEXT NOT NULL)");
                st.execute("CREATE TABLE IF NOT EXISTS users (season INTEGER,uuid TEXT,xp INTEGER,coins INTEGER,premium INTEGER,extra TEXT,PRIMARY KEY(season,uuid))");
            }
            season=Integer.parseInt(meta("season","1"));
            start=Long.parseLong(meta("start",String.valueOf(System.currentTimeMillis())));
            rotateSeasonIfNeeded();
            Objects.requireNonNull(getCommand("pase")).setExecutor(this);
            Objects.requireNonNull(getCommand("cpassadmin")).setExecutor(this);
            Bukkit.getPluginManager().registerEvents(this,this);
            if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI")!=null) new Papi(this).register();
            for(Player online:Bukkit.getOnlinePlayers()) user(online);
            Bukkit.getScheduler().runTaskTimer(this,()->{
                rotateSeasonIfNeeded();
                for (Player online:Bukkit.getOnlinePlayers()) {
                    User u=user(online);
                    if(System.currentTimeMillis()-u.lastActive<180000) advance(online,"play_min",1);
                }
                saveAll();
            },1200L,1200L);
            getLogger().info("CobraPass activado: 54 niveles, temporada "+season);
        } catch(Exception ex) {
            getLogger().log(Level.SEVERE,"No se pudo iniciar CobraPass",ex);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @Override public void onDisable() {
        saveAll();
        try {if(db!=null)db.close();}catch(Exception ex){getLogger().warning(ex.getMessage());}
    }
    private String meta(String key,String defaultValue) throws SQLException {
        try(PreparedStatement p=db.prepareStatement("SELECT v FROM meta WHERE k=?")) {
            p.setString(1,key);
            try(ResultSet r=p.executeQuery()) {if(r.next())return r.getString(1);}
        }
        setMeta(key,defaultValue);
        return defaultValue;
    }
    private void setMeta(String key,String val)throws SQLException {
        try(PreparedStatement p=db.prepareStatement("INSERT INTO meta(k,v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v")) {
            p.setString(1,key);p.setString(2,val);p.executeUpdate();
        }
    }
    private void rotateSeasonIfNeeded() {
        long duration=Math.max(1,getConfig().getInt("season-days",30))*86400000L;
        if(System.currentTimeMillis()-start>=duration) nextSeason();
    }
    private void nextSeason(){
        saveAll();
        season++;start=System.currentTimeMillis();cache.clear();
        try {setMeta("season",""+season);setMeta("start",""+start);}
        catch(SQLException e){getLogger().log(Level.SEVERE,"Error al cambiar temporada",e);}
        Bukkit.broadcastMessage(color("&a&lCOBRAPASS &7Nueva temporada disponible."));
    }
    private void loadFiles() {
        rewards=YamlConfiguration.loadConfiguration(new File(getDataFolder(),"rewards.yml"));
        questsFile=YamlConfiguration.loadConfiguration(new File(getDataFolder(),"quests.yml"));
        shop=YamlConfiguration.loadConfiguration(new File(getDataFolder(),"shop.yml"));
        daily.clear();weekly.clear();
        readQuests("daily",0,daily);
        for(int week=1;week<=4;week++) readQuests("weekly.week"+week,week,weekly);
    }
    private void readQuests(String path,int week,List<Quest> dest){
        for(Map<?,?> row:questsFile.getMapList(path))try{
            String id=String.valueOf(row.get("id"));
            String name=String.valueOf(row.get("name"));
            String type=String.valueOf(row.get("type"));
            int goal=((Number)row.get("goal")).intValue();
            int xp=((Number)row.get("xp")).intValue();
            Material icon=Material.valueOf(String.valueOf(row.get("icon")));
            dest.add(new Quest(id,name,type,goal,xp,week,icon));
        }catch(Exception e){getLogger().warning("Mision invalida en "+path+": "+e.getMessage());}
    }
    private User user(Player p){return user(p.getUniqueId());}
    private User user(UUID id){
        User cached=cache.get(id);
        if(cached!=null)return cached;
        User u=new User(id);
        try(PreparedStatement p=db.prepareStatement("SELECT xp,coins,premium,extra FROM users WHERE season=? AND uuid=?")){
            p.setInt(1,season);p.setString(2,id.toString());
            try(ResultSet r=p.executeQuery())if(r.next()){
                u.xp=r.getInt("xp");u.coins=r.getInt("coins");u.premium=r.getInt("premium")!=0;
                String extra=r.getString("extra");
                if(extra!=null&&!extra.isBlank()){
                    YamlConfiguration data=new YamlConfiguration();
                    data.loadFromString(extra);
                    if(data.isConfigurationSection("progress"))
                        for(String key:data.getConfigurationSection("progress").getKeys(false))
                            u.progress.put(key,data.getInt("progress."+key));
                    u.completed.addAll(data.getStringList("completed"));
                    u.claimed.addAll(data.getStringList("claimed"));
                    u.lastJoin=data.getLong("lastJoin");
                }
            }
        }catch(Exception ex){getLogger().log(Level.SEVERE,"Error al cargar usuario "+id,ex);}
        cache.put(id,u);
        return u;
    }
    private void save(User u){
        if(db==null)return;
        YamlConfiguration data=new YamlConfiguration();
        data.set("progress",u.progress);
        data.set("completed",new ArrayList<>(u.completed));
        data.set("claimed",new ArrayList<>(u.claimed));
        data.set("lastJoin",u.lastJoin);
        try(PreparedStatement p=db.prepareStatement(
                "INSERT INTO users(season,uuid,xp,coins,premium,extra) VALUES(?,?,?,?,?,?) "+
                "ON CONFLICT(season,uuid) DO UPDATE SET xp=excluded.xp,coins=excluded.coins,premium=excluded.premium,extra=excluded.extra")){
            p.setInt(1,season);p.setString(2,u.id.toString());
            p.setInt(3,u.xp);p.setInt(4,u.coins);p.setInt(5,u.premium?1:0);
            p.setString(6,data.saveToString());p.executeUpdate();
        }catch(Exception e){getLogger().log(Level.SEVERE,"Error al guardar usuario "+u.id,e);}
    }
    private void saveAll(){for(User u:cache.values())save(u);}
    int level(User u){return Math.min(54,1+u.xp/Math.max(1,getConfig().getInt("xp-per-level",200)));}
    int xpProgress(User u){return level(u)==54?200:u.xp%200;}
    long daysElapsed(){return Math.max(0,(System.currentTimeMillis()-start)/86400000L);}
    int daysLeft(){return (int)Math.max(0,getConfig().getInt("season-days",30)-daysElapsed());}
    private String color(String s){return ChatColor.translateAlternateColorCodes('&',s);}
    private void message(Player p,String s){p.sendMessage(color("&a&lCOBRAMC &8» &7"+s));}
    private String today(){
        try{return LocalDate.now(ZoneId.of(getConfig().getString("timezone","America/Lima"))).toString();}
        catch(Exception ex){return LocalDate.now().toString();}
    }
    private List<Quest> selectedDaily(Player p){
        List<Quest> result=new ArrayList<>(daily);
        long seed=p.getUniqueId().getMostSignificantBits()^p.getUniqueId().getLeastSignificantBits()^today().hashCode();
        Collections.shuffle(result,new Random(seed));
        return result.subList(0,Math.min(result.size(),Math.max(1,getConfig().getInt("daily-missions",5))));
    }
    private List<Quest> available(Player p){
        List<Quest> result=new ArrayList<>(selectedDaily(p));
        int unlocked=(int)Math.min(4,1+daysElapsed()/7);
        for(Quest q:weekly)if(q.week<=unlocked)result.add(q);
        return result;
    }
    private String key(Quest q){
        return q.week==0?"D:"+today()+":"+q.id:"W:"+season+":"+q.id;
    }
    private void advance(Player p,String type,int amount){
        if(amount<=0||p.getGameMode()==GameMode.CREATIVE||p.getGameMode()==GameMode.SPECTATOR)return;
        User u=user(p);
        boolean changed=false;
        for(Quest q:available(p)){
            if(!q.type.equals(type))continue;
            String key=key(q);
            if(u.completed.contains(key))continue;
            int next=Math.min(q.goal,u.progress.getOrDefault(key,0)+amount);
            u.progress.put(key,next);changed=true;
            if(next>=q.goal){
                u.completed.add(key);
                u.xp=Math.min(10600,u.xp+q.xp);
                message(p,"&aMision completada: &e"+q.name+" &7(+"+q.xp+" XP del pase)");
                save(u);
            }
        }
        if(changed)u.lastActive=System.currentTimeMillis();
    }
    private void touch(Player p){user(p).lastActive=System.currentTimeMillis();}
    private ItemStack item(Material type,String title,String... lore){
        ItemStack i=new ItemStack(type);
        ItemMeta m=i.getItemMeta();
        m.setDisplayName(color(title));
        m.setLore(Arrays.stream(lore).map(this::color).toList());
        i.setItemMeta(m);return i;
    }
    private Inventory blank(String view,int page,int size,String title){
        Page h=new Page(view,page);
        Inventory inv=Bukkit.createInventory(h,size,color(title));
        h.inventory=inv;
        for(int i=0;i<size;i++)inv.setItem(i,item(Material.BLACK_STAINED_GLASS_PANE," "));
        return inv;
    }
    private void open(Player p,String view,int page){
        switch(view){
            case "rewards" -> rewardsMenu(p,page);
            case "daily","week1","week2","week3","week4" -> questMenu(p,view);
            case "shop" -> shopMenu(p);
            case "info" -> infoMenu(p);
            default -> mainMenu(p);
        }
    }
    private void mainMenu(Player p){
        Inventory inv=blank("main",1,36,"&8COBRAMC &7| &aCobraPass");
        User u=user(p);
        inv.setItem(10,item(Material.CHEST,"&a&lRECOMPENSAS","&7Niveles Gratis y Premium","&eClick para abrir"));
        inv.setItem(12,item(Material.WRITABLE_BOOK,"&b&lMISIONES","&7Diarias y semanales","&eClick para abrir"));
        inv.setItem(14,item(Material.NETHER_STAR,"&6&lMI PASE","&7Nivel: &f"+level(u)+"/54","&7XP: &f"+xpProgress(u)+"/200","&7Pase: &f"+(u.premium?"PREMIUM":"GRATIS")));
        inv.setItem(16,item(Material.EMERALD,"&e&lTIENDA DE MONEDAS","&7CobraCoins: &f"+u.coins));
        inv.setItem(31,item(Material.CLOCK,"&aTemporada "+season,"&7Quedan "+daysLeft()+" dias"));
        p.openInventory(inv);
    }
    private String description(String action){
        String[] a=action.split(":");
        try{
            return switch(a[0]){
                case "money" -> "$"+String.format(Locale.US,"%.2fB",Long.parseLong(a[1])/1e9);
                case "coins" -> a[1]+" CobraCoins";
                case "key" -> a[2]+" llave(s) "+a[1];
                case "item" -> a[2]+" "+a[1].replace('_',' ').toLowerCase(Locale.ROOT);
                default -> action;
            };
        }catch(Exception ex){return action;}
    }
    private void rewardsMenu(Player p,int page){
        page=Math.max(1,Math.min(6,page));
        Inventory inv=blank("rewards",page,54,"&8Recompensas &7("+page+"/6)");
        User u=user(p);
        for(int j=0;j<9;j++){
            int n=(page-1)*9+j+1;
            inv.setItem(j,item(Material.KELP,"&eNivel "+n,"&7Gratis / Premium"));
            for(int track=0;track<2;track++){
                boolean prem=track==1;
                String key=(prem?"p:":"g:")+n;
                List<String> actions=rewards.getStringList("levels."+n+"."+(prem?"premium":"free"));
                boolean blocked=level(u)<n||(prem&&!u.premium);
                boolean claimed=u.claimed.contains(key);
                Material mat=claimed?Material.GRAY_STAINED_GLASS_PANE:
                    blocked?Material.RED_STAINED_GLASS_PANE:Material.LIME_STAINED_GLASS_PANE;
                List<String> lore=new ArrayList<>();
                lore.add("&8COBRAMC");
                for(String a:actions)lore.add("&7- &f"+description(a));
                lore.add(claimed?"&7Ya reclamado":blocked?"&cBloqueado":"&eClick para reclamar");
                inv.setItem((prem?18:9)+j,item(mat,(prem?"&6Premium":"&aGratis")+" &f- Nivel "+n,lore.toArray(new String[0])));
            }
        }
        inv.setItem(45,item(Material.ARROW,"&cPagina anterior"));
        inv.setItem(48,item(Material.COMPASS,"&bMenu principal"));
        inv.setItem(49,item(Material.BOOK,"&eNivel "+level(u)+"/54","&7XP "+xpProgress(u)+"/200"));
        inv.setItem(53,item(Material.ARROW,"&aPagina siguiente"));
        p.openInventory(inv);
    }
    private void questMenu(Player p,String view){
        boolean isDaily=view.equals("daily");
        int week=isDaily?0:Integer.parseInt(view.substring(4));
        Inventory inv=blank(view,1,54,isDaily?"&8Misiones diarias":"&8Misiones - Semana "+week);
        User u=user(p);
        List<Quest> list=new ArrayList<>();
        if(isDaily)list.addAll(selectedDaily(p));
        else for(Quest q:weekly)if(q.week==week)list.add(q);
        boolean locked=week>0&&daysElapsed()<(week-1)*7L;
        for(int i=0;i<list.size()&&i<36;i++){
            Quest q=list.get(i);
            String k=key(q);
            int current=u.progress.getOrDefault(k,0);
            boolean done=u.completed.contains(k);
            inv.setItem(9+i,item(q.icon,(done?"&a✔ ":"&e")+q.name,
                "&7Progreso: &f"+current+"/"+q.goal,
                "&7Premio: &a"+q.xp+" XP del pase",
                locked?"&cSemana bloqueada":done?"&aCompletada":"&eSe completa automaticamente"));
        }
        inv.setItem(45,item(Material.CLOCK,"&bDiarias"));
        for(int i=1;i<=4;i++)inv.setItem(45+i,item(Material.BOOK,"&eSemana "+i));
        inv.setItem(53,item(Material.COMPASS,"&bMenu principal"));
        p.openInventory(inv);
    }
    private void shopMenu(Player p){
        Inventory inv=blank("shop",1,36,"&8Tienda de CobraCoins");
        List<String> ids=shop.getConfigurationSection("items")==null?List.of():
            new ArrayList<>(shop.getConfigurationSection("items").getKeys(false));
        for(int i=0;i<ids.size()&&i<5;i++){
            String path="items."+ids.get(i)+".";
            Material mat=Material.matchMaterial(shop.getString(path+"icon","CHEST"));
            if(mat==null)mat=Material.CHEST;
            inv.setItem(10+i*2,item(mat,shop.getString(path+"name","&ePremio"),
                "&7Precio: &e"+shop.getInt(path+"price")+" monedas",
                "&eClick para comprar"));
        }
        inv.setItem(31,item(Material.COMPASS,"&bMenu principal"));
        p.openInventory(inv);
    }
    private void infoMenu(Player p){
        User u=user(p);
        Inventory inv=blank("info",1,27,"&8Informacion del CobraPass");
        inv.setItem(13,item(Material.NETHER_STAR,"&aTu CobraPass",
            "&7Nivel: &f"+level(u)+"/54",
            "&7XP: &f"+xpProgress(u)+"/200",
            "&7Premium: &f"+(u.premium?"Activo":"Inactivo"),
            "&7CobraCoins: &f"+u.coins,
            "&7Temporada: &f"+season,
            "&7Quedan: &f"+daysLeft()+" dias"));
        inv.setItem(22,item(Material.COMPASS,"&bVolver"));
        p.openInventory(inv);
    }
    @EventHandler public void onGui(InventoryClickEvent e){
        if(e.getView().getTopInventory().getHolder() instanceof Page page){
            e.setCancelled(true);
            if(e.getRawSlot()<0||e.getRawSlot()>=e.getView().getTopInventory().getSize())return;
            if(!(e.getWhoClicked() instanceof Player p))return;
            int s=e.getRawSlot();
            String next=null;int np=1;
            switch(page.kind){
                case "main" -> {
                    if(s==10){next="rewards";}
                    else if(s==12){next="daily";}
                    else if(s==14){next="info";}
                    else if(s==16){next="shop";}
                }
                case "rewards" -> {
                    if(s>=9&&s<=17)claim(p,(page.number-1)*9+s-8,false);
                    else if(s>=18&&s<=26)claim(p,(page.number-1)*9+s-17,true);
                    if(s==45){next="rewards";np=Math.max(1,page.number-1);}
                    else if(s==53){next="rewards";np=Math.min(6,page.number+1);}
                    else if(s==48){next="main";}
                    else {next="rewards";np=page.number;}
                }
                case "daily","week1","week2","week3","week4" -> {
                    if(s==45)next="daily";
                    else if(s>=46&&s<=49)next="week"+(s-45);
                    else if(s==53)next="main";
                }
                case "shop" -> {
                    if(s==31)next="main";
                    else if(s>=10&&s<=18&&s%2==0){
                        List<String> ids=shop.getConfigurationSection("items")==null?List.of():
                            new ArrayList<>(shop.getConfigurationSection("items").getKeys(false));
                        int idx=(s-10)/2;if(idx<ids.size())buy(p,ids.get(idx));
                        next="shop";
                    }
                }
                case "info" -> {if(s==22)next="main";}
            }
            if(next!=null){String view=next;int number=np;Bukkit.getScheduler().runTask(this,()->open(p,view,number));}
        }else if(e.getInventory() instanceof MerchantInventory&&e.getRawSlot()==2&&e.getCurrentItem()!=null&&
                e.getCurrentItem().getType()!=Material.AIR&&e.getWhoClicked() instanceof Player p){
            touch(p);advance(p,"trade",1);
        }
    }
    @EventHandler public void onDrag(InventoryDragEvent e){
        if(e.getView().getTopInventory().getHolder() instanceof Page)e.setCancelled(true);
    }
    private void claim(Player p,int n,boolean premium){
        User u=user(p);
        if(level(u)<n){message(p,"&cNivel "+n+" bloqueado.");return;}
        if(premium&&!u.premium){message(p,"&cNecesitas Premium.");return;}
        String key=(premium?"p:":"g:")+n;
        if(u.claimed.contains(key)){message(p,"&eYa reclamaste este premio.");return;}
        List<String> actions=rewards.getStringList("levels."+n+"."+(premium?"premium":"free"));
        if(actions.isEmpty()){message(p,"&cPremio sin configurar.");return;}
        u.claimed.add(key);save(u);
        boolean ok=true;
        for(String a:actions)if(!execute(p,a))ok=false;
        if(ok)message(p,"&aPremio del nivel "+n+" entregado.");
        else {
            message(p,"&cAlgun comando de entrega fallo. Contacta al staff.");
            getLogger().severe("Entrega parcial: "+p.getName()+" nivel="+n+" premium="+premium);
        }
    }
    private boolean execute(Player p,String action){
        try{
            String[] a=action.split(":");
            return switch(a[0]){
                case "money" -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    "eco give "+p.getName()+" "+Long.parseLong(a[1]));
                case "coins" -> {
                    user(p).coins+=Integer.parseInt(a[1]);save(user(p));yield true;
                }
                case "key" -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    "crazycrates give physical "+a[1]+" "+Integer.parseInt(a[2])+" "+p.getName());
                case "item" -> {
                    Material mat=Material.valueOf(a[1]);
                    int remaining=Integer.parseInt(a[2]);
                    if(remaining<1||remaining>10000)yield false;
                    while(remaining>0){
                        int count=Math.min(remaining,mat.getMaxStackSize());
                        ItemStack st=new ItemStack(mat,count);
                        Map<Integer,ItemStack> extra=p.getInventory().addItem(st);
                        for(ItemStack spill:extra.values())p.getWorld().dropItemNaturally(p.getLocation(),spill);
                        remaining-=count;
                    }
                    yield true;
                }
                default -> false;
            };
        }catch(Exception ex){
            getLogger().log(Level.SEVERE,"Accion fallida: "+action+" jugador "+p.getName(),ex);
            return false;
        }
    }
    private void buy(Player p,String id){
        String prefix="items."+id+".";
        int price=shop.getInt(prefix+"price",-1);
        if(price<0)return;
        User u=user(p);
        if(u.coins<price){message(p,"&cTe faltan CobraCoins.");return;}
        List<String> acts=shop.getStringList(prefix+"actions");
        if(acts.isEmpty())return;
        u.coins-=price;save(u);
        boolean ok=true;for(String a:acts)if(!execute(p,a))ok=false;
        message(p,ok?"&aCompra completada.":"&cError al entregar; contacta al staff.");
    }

    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        if(command.getName().equalsIgnoreCase("cpassadmin"))return admin(sender,args);
        if(!(sender instanceof Player p)){sender.sendMessage("Solo jugadores.");return true;}
        if(args.length==0){open(p,"main",1);return true;}
        String sub=args[0].toLowerCase(Locale.ROOT);
        switch(sub){
            case "recompensas","rewards" -> open(p,"rewards",1);
            case "misiones","quests" -> open(p,"daily",1);
            case "tienda","shop" -> open(p,"shop",1);
            case "info" -> open(p,"info",1);
            default -> open(p,"main",1);
        }
        return true;
    }
    private boolean admin(CommandSender sender,String[] args){
        if(!sender.hasPermission("cobrapass.admin"))return true;
        if(args.length>=2&&args[0].equalsIgnoreCase("season")&&args[1].equalsIgnoreCase("next")){
            nextSeason();sender.sendMessage("Temporada "+season+" iniciada.");return true;
        }
        if(args.length>=1&&args[0].equalsIgnoreCase("reload")){
            saveAll();reloadConfig();loadFiles();sender.sendMessage("CobraPass recargado.");return true;
        }
        if(args.length>=3){
            UUID id;
            try{id=UUID.fromString(args[1]);}
            catch(IllegalArgumentException ex){id=Bukkit.getOfflinePlayer(args[1]).getUniqueId();}
            User u=user(id);
            if(args[0].equalsIgnoreCase("premium")){
                u.premium=args[2].equalsIgnoreCase("on")||args[2].equalsIgnoreCase("true");
                save(u);sender.sendMessage("Premium actualizado.");return true;
            }
            if(args[0].equalsIgnoreCase("addxp")){
                try{u.xp=Math.max(0,Math.min(10600,u.xp+Integer.parseInt(args[2])));save(u);
                    sender.sendMessage("XP actualizado.");}
                catch(NumberFormatException ex){sender.sendMessage("Cantidad invalida.");}
                return true;
            }
            if(args.length>=4&&args[0].equalsIgnoreCase("resetclaim")){
                try{int n=Integer.parseInt(args[3]);
                    u.claimed.remove((args[2].equalsIgnoreCase("premium")?"p:":"g:")+n);
                    save(u);sender.sendMessage("Premio habilitado de nuevo.");}
                catch(NumberFormatException ex){sender.sendMessage("Nivel invalido.");}
                return true;
            }
        }
        sender.sendMessage("Comandos: /cpassadmin premium <jugador> on|off");
        sender.sendMessage("/cpassadmin addxp <jugador> <cantidad>");
        sender.sendMessage("/cpassadmin resetclaim <jugador> gratis|premium <nivel>");
        sender.sendMessage("/cpassadmin season next | /cpassadmin reload");
        return true;
    }
    @EventHandler public void join(PlayerJoinEvent e){
        Player p=e.getPlayer();User u=user(p);
        long now=System.currentTimeMillis();
        if(now-u.lastJoin>=900000){u.lastJoin=now;advance(p,"join",1);}
        if(p.getLevel()>=40)advance(p,"reach_level",1);
        touch(p);save(u);
    }
    @EventHandler public void quit(PlayerQuitEvent e){save(user(e.getPlayer()));cache.remove(e.getPlayer().getUniqueId());}
    @EventHandler(ignoreCancelled=true) public void block(BlockBreakEvent e){
        Player p=e.getPlayer();touch(p);
        Material m=e.getBlock().getType();advance(p,"break_any",1);
        if(m==Material.STONE)advance(p,"break_stone",1);
        if(m==Material.DEEPSLATE)advance(p,"break_deepslate",1);
        if(Tag.LOGS.isTagged(m))advance(p,"break_log",1);
        if(m==Material.IRON_ORE||m==Material.DEEPSLATE_IRON_ORE)advance(p,"break_iron",1);
        if(m==Material.DIAMOND_ORE||m==Material.DEEPSLATE_DIAMOND_ORE)advance(p,"break_diamond",1);
        if(e.getBlock().getBlockData() instanceof Ageable crop&&crop.getAge()==crop.getMaximumAge())
            advance(p,"harvest",1);
    }
    @EventHandler(ignoreCancelled=true) public void place(BlockPlaceEvent e){touch(e.getPlayer());advance(e.getPlayer(),"place",1);}
    @EventHandler(ignoreCancelled=true) public void kills(EntityDeathEvent e){
        Player killer=e.getEntity().getKiller();if(killer==null)return;
        touch(killer);
        if(e.getEntity() instanceof Monster)advance(killer,"kill_hostile",1);
        if(e.getEntity() instanceof Zombie)advance(killer,"kill_zombie",1);
        if(e.getEntity() instanceof Skeleton)advance(killer,"kill_skeleton",1);
        if(e.getEntity() instanceof Spider)advance(killer,"kill_spider",1);
        if(e.getEntity() instanceof Blaze)advance(killer,"kill_blaze",1);
    }
    @EventHandler public void pvp(PlayerDeathEvent e){
        Player v=e.getEntity(),killer=v.getKiller();
        if(killer==null||killer.equals(v))return;
        String key=killer.getUniqueId()+":"+v.getUniqueId();
        long now=System.currentTimeMillis();
        if(now-pvpCooldowns.getOrDefault(key,0L)<1800000)return;
        pvpCooldowns.put(key,now);touch(killer);advance(killer,"kill_pvp",1);
    }
    @EventHandler(ignoreCancelled=true) public void shoot(ProjectileLaunchEvent e){
        if(e.getEntity().getShooter() instanceof Player p){touch(p);advance(p,"shoot",1);}
    }
    @EventHandler(ignoreCancelled=true) public void interact(PlayerInteractEvent e){
        if(e.getHand()==EquipmentSlot.HAND&&e.getAction()==Action.RIGHT_CLICK_BLOCK){
            touch(e.getPlayer());advance(e.getPlayer(),"interact",1);
        }
    }
    @EventHandler(ignoreCancelled=true) public void move(PlayerMoveEvent e){
        if(e.getTo()==null||!e.getFrom().getWorld().equals(e.getTo().getWorld()))return;
        Player p=e.getPlayer();
        if(p.isFlying()||p.isGliding()||p.isInsideVehicle())return;
        double dist=e.getFrom().distance(e.getTo());if(dist<0.01||dist>8)return;
        User u=user(p);u.walkRemainder+=dist;
        if(u.walkRemainder>=1){
            int blocks=(int)u.walkRemainder;u.walkRemainder-=blocks;
            touch(p);advance(p,"walk",blocks);
        }
    }
    @EventHandler(ignoreCancelled=true) public void tame(EntityTameEvent e){
        if(e.getOwner() instanceof Player p&&e.getEntityType()==EntityType.WOLF){
            touch(p);advance(p,"tame_wolf",1);
        }
    }
    @EventHandler(ignoreCancelled=true) public void heal(EntityRegainHealthEvent e){
        if(e.getEntity() instanceof Player p){touch(p);advance(p,"heal",(int)Math.ceil(e.getAmount()));}
    }
    @EventHandler public void chat(AsyncPlayerChatEvent e){
        if(e.getMessage().trim().equalsIgnoreCase("COBRAMCTHEBEST")){
            Bukkit.getScheduler().runTask(this,()->{touch(e.getPlayer());advance(e.getPlayer(),"chat_phrase",1);});
        }
    }
    @EventHandler public void lvl(PlayerLevelChangeEvent e){
        if(e.getNewLevel()>=40)advance(e.getPlayer(),"reach_level",1);
    }
    @EventHandler(ignoreCancelled=true) public void fish(PlayerFishEvent e){
        if(e.getState()==PlayerFishEvent.State.CAUGHT_FISH){touch(e.getPlayer());advance(e.getPlayer(),"fish",1);}
    }
    @EventHandler(ignoreCancelled=true) public void breed(EntityBreedEvent e){
        if(e.getBreeder() instanceof Player p){touch(p);advance(p,"breed",1);}
    }
    @EventHandler(ignoreCancelled=true) public void craft(CraftItemEvent e){
        if(e.getWhoClicked() instanceof Player p){touch(p);advance(p,"craft",1);}
    }
    @EventHandler(ignoreCancelled=true) public void enchant(EnchantItemEvent e){
        touch(e.getEnchanter());advance(e.getEnchanter(),"enchant",1);
    }
    @EventHandler public void exp(PlayerExpChangeEvent e){
        if(e.getAmount()>0){touch(e.getPlayer());advance(e.getPlayer(),"gain_xp",e.getAmount());}
    }
    @EventHandler(ignoreCancelled=true) public void damage(EntityDamageByEntityEvent e){
        Player p=null;
        if(e.getDamager() instanceof Player damager)p=damager;
        else if(e.getDamager() instanceof Projectile projectile&&projectile.getShooter() instanceof Player shooter)p=shooter;
        if(p!=null&&e.getEntity() instanceof Monster){
            touch(p);advance(p,"damage_hostile",(int)Math.ceil(e.getFinalDamage()));
        }
    }
    static final class Papi extends PlaceholderExpansion {
        private final CobraPass plugin;
        Papi(CobraPass plugin){this.plugin=plugin;}
        @Override public String getIdentifier(){return "cobrapass";}
        @Override public String getAuthor(){return "CobraMC";}
        @Override public String getVersion(){return "1.0.0";}
        @Override public boolean persist(){return true;}
        @Override public String onPlaceholderRequest(Player p,String param){
            if(p==null)return "";
            User u=plugin.user(p);
            return switch(param.toLowerCase(Locale.ROOT)){
                case "level" -> ""+plugin.level(u);
                case "xp" -> ""+plugin.xpProgress(u);
                case "xp_required" -> "200";
                case "xp_total" -> ""+u.xp;
                case "type" -> u.premium?"PREMIUM":"GRATIS";
                case "premium" -> u.premium?"true":"false";
                case "coins" -> ""+u.coins;
                case "season" -> ""+plugin.season;
                case "days_left" -> ""+plugin.daysLeft();
                default -> null;
            };
        }
    }
}
