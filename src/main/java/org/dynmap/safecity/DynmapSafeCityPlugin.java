package org.dynmap.safecity;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.dynmap.DynmapAPI;
import org.dynmap.markers.AreaMarker;
import org.dynmap.markers.MarkerAPI;
import org.dynmap.markers.MarkerSet;

import me.jayfella.SafeCity.SafeCityContext;
import me.jayfella.SafeCity.SafeCityPlugin;
import me.jayfella.SafeCity.SafeCitySubZone;
import me.jayfella.SafeCity.SafeCityZone;
import me.jayfella.SafeCity.Core.GenericZone;
import me.jayfella.SafeCity.Core.ThinLocation;

public class DynmapSafeCityPlugin extends JavaPlugin {
    private static Logger log;
    private static final String DEF_INFOWINDOW = "<div class=\"infowindow\"><span style=\"font-size:120%;\">%regionname%</span><br /> Owner <span style=\"font-weight:bold;\">%playerowners%</span><br />Flags<br /><span style=\"font-weight:bold;\">%flags%</span></div>";
    public static final String BOOST_FLAG = "dynmap-boost";
    Plugin dynmap;
    DynmapAPI api;
    MarkerAPI markerapi;
    SafeCityPlugin safecity;
    
    FileConfiguration cfg;
    MarkerSet set;
    long updperiod;
    boolean use3d;
    String infowindow;
    AreaStyle defstyle;
    Map<String, AreaStyle> cusstyle;
    Map<String, AreaStyle> cuswildstyle;
    Map<String, AreaStyle> ownerstyle;
    Set<String> visible;
    Set<String> hidden;
    boolean stop; 
    int maxdepth;

    @Override
    public void onLoad() {
        log = this.getLogger();
    }
    
    private static class AreaStyle {
        String strokecolor;
        String unownedstrokecolor;
        double strokeopacity;
        int strokeweight;
        String fillcolor;
        double fillopacity;
        String label;
        boolean boost;

        AreaStyle(FileConfiguration cfg, String path, AreaStyle def) {
            strokecolor = cfg.getString(path+".strokeColor", def.strokecolor);
            unownedstrokecolor = cfg.getString(path+".unownedStrokeColor", def.unownedstrokecolor);
            strokeopacity = cfg.getDouble(path+".strokeOpacity", def.strokeopacity);
            strokeweight = cfg.getInt(path+".strokeWeight", def.strokeweight);
            fillcolor = cfg.getString(path+".fillColor", def.fillcolor);
            fillopacity = cfg.getDouble(path+".fillOpacity", def.fillopacity);
            label = cfg.getString(path+".label", null);
            boost = cfg.getBoolean(path + ".boost", def.boost);
        }

        AreaStyle(FileConfiguration cfg, String path) {
            strokecolor = cfg.getString(path+".strokeColor", "#FF0000");
            unownedstrokecolor = cfg.getString(path+".unownedStrokeColor", "#00FF00");
            strokeopacity = cfg.getDouble(path+".strokeOpacity", 0.8);
            strokeweight = cfg.getInt(path+".strokeWeight", 3);
            fillcolor = cfg.getString(path+".fillColor", "#FF0000");
            fillopacity = cfg.getDouble(path+".fillOpacity", 0.35);
            boost = cfg.getBoolean(path + ".boost", false);
        }
    }
    
    public static void info(String msg) {
        log.log(Level.INFO, msg);
    }
    public static void severe(String msg) {
        log.log(Level.SEVERE, msg);
    }

    private class SafeCityUpdate implements Runnable {
        public void run() {
            if(!stop)
                updateRegions();
        }
    }
    
    private Map<String, AreaMarker> resareas = new HashMap<String, AreaMarker>();

    private String formatInfoWindow(GenericZone zone, String parent, AreaMarker m) {
        String v = "<div class=\"regioninfo\">"+infowindow+"</div>";
        v = v.replace("%regionname%", m.getLabel());
        v = v.replace("%playerowners%", zone.getFounder());
        v = v.replace("%playerrenters%", zone.isRented()?zone.getRenter():"");
        if(parent != null)
            v = v.replace("%parent%", parent);
        else
            v = v.replace("%parent%", "");
        String flgs = "";
        flgs += "Rented: " + zone.isRented() + "<br/>";
        flgs += "For Rent: " + zone.isForRent() + "<br/>";
        flgs += "Fly Allowed: " + zone.isFlyAllowed() + "<br/>";
        flgs += "PVP Enabled: " + zone.isPvpEnabled() + "<br/>";
        flgs += "For Sale: " + zone.isForSale() + "<br/>";
        flgs += "Vine Growth: " + zone.getVineGrowth() + "<br/>";
        v = v.replace("%flags%", flgs);
        return v;
    }
    
    private boolean isVisible(String id, String worldname) {
        if((visible != null) && (visible.size() > 0)) {
            if((visible.contains(id) == false) && (visible.contains("world:" + worldname) == false) &&
                    (visible.contains(worldname + "/" + id) == false)) {
                return false;
            }
        }
        if((hidden != null) && (hidden.size() > 0)) {
            if(hidden.contains(id) || hidden.contains("world:" + worldname) || hidden.contains(worldname + "/" + id))
                return false;
        }
        return true;
    }
    
    private void addStyle(String zoneid, String worldid, AreaMarker m, GenericZone zone, String parent) {
        AreaStyle as = cusstyle.get(worldid + "/" + zoneid);
        if(as == null) {
            as = cusstyle.get(zoneid);
        }
        if(as == null) {    /* Check for wildcard style matches */
            for(String wc : cuswildstyle.keySet()) {
                String[] tok = wc.split("\\|");
                if((tok.length == 1) && zoneid.startsWith(tok[0]))
                    as = cuswildstyle.get(wc);
                else if((tok.length >= 2) && zoneid.startsWith(tok[0]) && zoneid.endsWith(tok[1]))
                    as = cuswildstyle.get(wc);
            }
        }
        if(as == null) {    /* Check for owner style matches */
            if(ownerstyle.isEmpty() != true) {
                String owner = zone.getFounder();
                if (owner != null) {
                    as = ownerstyle.get(owner.toLowerCase());
                }
            }
        }
        if(as == null)
            as = defstyle;

        boolean unowned = false;
        if (zone.getFounder() == null) {
            unowned = true;
        }
        int sc = 0xFF0000;
        int fc = 0xFF0000;
        try {
            if(unowned)
                sc = Integer.parseInt(as.unownedstrokecolor.substring(1), 16);
            else
                sc = Integer.parseInt(as.strokecolor.substring(1), 16);
           fc = Integer.parseInt(as.fillcolor.substring(1), 16);
        } catch (NumberFormatException nfx) {
        }
        m.setLineStyle(as.strokeweight, as.strokeopacity, sc);
        m.setFillStyle(as.fillopacity, fc);
        if(as.label != null) {
            m.setLabel(as.label);
        }
        m.setBoostFlag(as.boost);
    }
    
    /* Handle specific zone (parent=null for zones, != null for subzones) */
    private void handleZone(GenericZone zone, String parent, Map<String, AreaMarker> newmap) {
        String name = zone.getName();
        double[] x = null;
        double[] z = null;
        World world = zone.getWorld();
                
        /* Handle areas */
        if(isVisible(zone.getName(), zone.getWorld().getName())) {
            String id = zone.getName();
            ThinLocation l0 = zone.getLesserCorner();
            ThinLocation l1 = zone.getGreaterCorner();

            /* Make outline */
            x = new double[4];
            z = new double[4];
            x[0] = l0.getBlockX(); z[0] = l0.getBlockZ();
            x[1] = l0.getBlockX(); z[1] = l1.getBlockZ() + 1.0;
            x[2] = l1.getBlockX() + 1.0; z[2] = l1.getBlockZ() + 1.0;
            x[3] = l1.getBlockX() + 1.0; z[3] = l0.getBlockZ();
            
            String markerid;
            if (parent != null)
                markerid = world.getName() + "_" + parent + "_" + id;
            else
                markerid = world.getName() + "_" + id;
            AreaMarker m = resareas.remove(markerid); /* Existing area? */
            if(m == null) {
                m = set.createAreaMarker(markerid, name, false, world.getName(), x, z, false);
                if(m == null)
                    return;
            }
            else {
                m.setCornerLocations(x, z); /* Replace corner locations */
                m.setLabel(name);   /* Update label */
            }
            if(use3d) { /* If 3D? */
                m.setRangeY(l1.getBlockY() + 1.0, l0.getBlockY());
            }            
            /* Set line and fill properties */
            addStyle(id, world.getName(), m, zone, parent);

            /* Build popup */
            String desc = formatInfoWindow(zone, parent, m);

            m.setDescription(desc); /* Set popup */

            /* Add to map */
            newmap.put(markerid, m);
        }
    }
    
    /* Update safecity region information */
    private void updateRegions() {
        Map<String,AreaMarker> newmap = new HashMap<String,AreaMarker>(); /* Build new map */
 
        SafeCityContext ctx = safecity.getContext();
        
        HashMap<Integer, String> zone_name_by_id = new HashMap<Integer, String>();
        /* Loop through zones */
        if (maxdepth > 0) {
            for (SafeCityZone zone : ctx.getZones()) {
                handleZone(zone, null, newmap);
                zone_name_by_id.put(zone.getId(), zone.getName());
            }
        }
        /* Loop through subzones */
        if (maxdepth > 1) {
            for (SafeCitySubZone subzone : ctx.getSubZones()) {
                handleZone(subzone, zone_name_by_id.get(subzone.getParentId()), newmap);
            }
        }
        /* Now, review old map - anything left is gone */
        for(AreaMarker oldm : resareas.values()) {
            oldm.deleteMarker();
        }
        /* And replace with new map */
        resareas = newmap;
        
        getServer().getScheduler().scheduleSyncDelayedTask(this, new SafeCityUpdate(), updperiod);
    }

    private class OurServerListener implements Listener {
        @SuppressWarnings("unused")
        @EventHandler(priority=EventPriority.MONITOR)
        public void onPluginEnable(PluginEnableEvent event) {
            Plugin p = event.getPlugin();
            String name = p.getDescription().getName();
            if(name.equals("dynmap") || name.equals("WorldGuard")) {
                if(dynmap.isEnabled() && safecity.isEnabled())
                    activate();
            }
        }
    }
    
    public void onEnable() {
        info("initializing");
        PluginManager pm = getServer().getPluginManager();
        /* Get dynmap */
        dynmap = pm.getPlugin("dynmap");
        if(dynmap == null) {
            severe("Cannot find dynmap!");
            return;
        }
        api = (DynmapAPI)dynmap; /* Get API */
        /* Get SafeCity */
        Plugin p = pm.getPlugin("SafeCity");
        if(p == null) {
            severe("Cannot find SafeCity!");
            return;
        }
        safecity = (SafeCityPlugin)p;
        
        getServer().getPluginManager().registerEvents(new OurServerListener(), this);        
        
        /* If both enabled, activate */
        if(dynmap.isEnabled() && safecity.isEnabled())
            activate();
        /* Start up metrics */
        try {
            MetricsLite ml = new MetricsLite(this);
            ml.start();
        } catch (IOException iox) {
            
        }
    }
    
    private boolean reload = false;
    
    private void activate() {        
        /* Now, get markers API */
        markerapi = api.getMarkerAPI();
        if(markerapi == null) {
            severe("Error loading dynmap marker API!");
            return;
        }
        /* Load configuration */
        if(reload) {
            this.reloadConfig();
        }
        else {
            reload = true;
        }
        FileConfiguration cfg = getConfig();
        cfg.options().copyDefaults(true);   /* Load defaults, if needed */
        this.saveConfig();  /* Save updates, if needed */
        
        /* Now, add marker set for mobs (make it transient) */
        set = markerapi.getMarkerSet("safecity.markerset");
        if(set == null)
            set = markerapi.createMarkerSet("safecity.markerset", cfg.getString("layer.name", "SafeCity"), null, false);
        else
            set.setMarkerSetLabel(cfg.getString("layer.name", "SafeCity"));
        if(set == null) {
            severe("Error creating marker set");
            return;
        }
        int minzoom = cfg.getInt("layer.minzoom", 0);
        if(minzoom > 0)
            set.setMinZoom(minzoom);
        set.setLayerPriority(cfg.getInt("layer.layerprio", 10));
        set.setHideByDefault(cfg.getBoolean("layer.hidebydefault", false));
        use3d = cfg.getBoolean("use3dregions", false);
        infowindow = cfg.getString("infowindow", DEF_INFOWINDOW);
        maxdepth = cfg.getInt("maxdepth", 16);

        /* Get style information */
        defstyle = new AreaStyle(cfg, "regionstyle");
        cusstyle = new HashMap<String, AreaStyle>();
        ownerstyle = new HashMap<String, AreaStyle>();
        cuswildstyle = new HashMap<String, AreaStyle>();
        ConfigurationSection sect = cfg.getConfigurationSection("custstyle");
        if(sect != null) {
            Set<String> ids = sect.getKeys(false);
            
            for(String id : ids) {
                if(id.indexOf('|') >= 0)
                    cuswildstyle.put(id, new AreaStyle(cfg, "custstyle." + id, defstyle));
                else
                    cusstyle.put(id, new AreaStyle(cfg, "custstyle." + id, defstyle));
            }
        }
        sect = cfg.getConfigurationSection("ownerstyle");
        if(sect != null) {
            Set<String> ids = sect.getKeys(false);
            
            for(String id : ids) {
                ownerstyle.put(id.toLowerCase(), new AreaStyle(cfg, "ownerstyle." + id, defstyle));
            }
        }
        List<String> vis = cfg.getStringList("visibleregions");
        if(vis != null) {
            visible = new HashSet<String>(vis);
        }
        List<String> hid = cfg.getStringList("hiddenregions");
        if(hid != null) {
            hidden = new HashSet<String>(hid);
        }

        /* Set up update job - based on periond */
        int per = cfg.getInt("update.period", 300);
        if(per < 15) per = 15;
        updperiod = (long)(per*20);
        stop = false;
        
        getServer().getScheduler().scheduleSyncDelayedTask(this, new SafeCityUpdate(), 40);   /* First time is 2 seconds */
        
        info("version " + this.getDescription().getVersion() + " is activated");
    }

    public void onDisable() {
        if(set != null) {
            set.deleteMarkerSet();
            set = null;
        }
        resareas.clear();
        stop = true;
    }

}
