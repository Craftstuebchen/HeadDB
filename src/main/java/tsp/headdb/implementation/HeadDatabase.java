package tsp.headdb.implementation;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.plugin.java.JavaPlugin;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import tsp.headdb.HeadDB;
import tsp.headdb.api.event.DatabaseUpdateEvent;
import tsp.headdb.util.Log;
import tsp.headdb.util.Utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.annotation.Nonnull;

/**
 * This is the Database that holds all heads
 *
 * @author TheSilentPro
 */
public class HeadDatabase {

    private final JavaPlugin plugin;
    private final EnumMap<Category, List<Head>> heads = new EnumMap<>(Category.class);
    private long refresh;
    private int timeout;
    private long updated;
    private int nextId; // Internal only

    public HeadDatabase(JavaPlugin plugin) {
        this.plugin = plugin;
        this.refresh = 3600;
        this.timeout = 5000;
    }

    public Head getHeadByValue(String value) {
        List<Head> fetched = getHeads();
        for (Head head : fetched) {
            if (head.getValue().equals(value)) {
                return head;
            }
        }

        return null;
    }

    public Head getHeadByID(int id) {
        List<Head> fetched = getHeads();
        for (Head head : fetched) {
            if (head.getId() == id) {
                return head;
            }
        }

        return null;
    }

    public Head getHeadByUniqueId(UUID uuid) {
        List<Head> fetched = getHeads();
        for (Head head : fetched) {
            if (head.getUniqueId().equals(uuid)) {
                return head;
            }
        }

        return null;
    }

    public List<Head> getHeadsByTag(String tag) {
        List<Head> result = new ArrayList<>();
        List<Head> fetched = getHeads();
        tag = tag.toLowerCase(Locale.ROOT);
        for (Head head : fetched) {
            for (String t : head.getTags()) {
                if (t.toLowerCase(Locale.ROOT).contains(tag)) {
                    result.add(head);
                }
            }
        }

        return result;
    }

    public List<Head> getHeadsByName(Category category, String name) {
        List<Head> result = new ArrayList<>();
        List<Head> fetched = getHeads(category);
        for (Head head : fetched) {
            String hName = ChatColor.stripColor(head.getName().toLowerCase(Locale.ROOT));
            if (hName.contains(ChatColor.stripColor(name.toLowerCase(Locale.ROOT)))) {
                result.add(head);
            }
        }

        return result;
    }

    public List<Head> getHeadsByName(String name) {
        List<Head> result = new ArrayList<>();
        for (Category category : Category.getCache()) {
            result.addAll(getHeadsByName(category, name));
        }

        return result;
    }

    @Nonnull
    public List<Head> getHeads(Category category) {
        return heads.get(category) != null ? Collections.unmodifiableList(heads.get(category)) : new ArrayList<>();
    }

    /**
     * Gets all heads from the cache if available.
     *
     * @return List containing each head in its category.
     */
    @Nonnull
    public List<Head> getHeads() {
        List<Head> result = new ArrayList<>();
        for (Category category : heads.keySet()) {
            result.addAll(getHeads(category));
        }
        return result;
    }

    public void getHeadsNoCache(Consumer<Map<Category, List<Head>>> resultSet) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, task -> {
            Log.debug("[" + plugin.getName() + "] Updating database... ");
            EnumMap<Category, List<Head>> result = new EnumMap<>(Category.class);
            Category[] categories = Category.getCache();

            for (Category category : categories) {
                Log.debug("Caching heads from: " + category.getName());
                List<Head> results = new ArrayList<>();
                try {
                    // First the original api is fetched
                    results = gather("https://minecraft-heads.com/scripts/api.php?cat=" + category.getName() + "&tags=true", category);
                } catch (ParseException | IOException e) {
                    Log.debug("[" + plugin.getName() + "] Failed to fetch heads (no-cache) from category " + category.getName() + " | Stack Trace:");
                    Log.debug(e);
                    Log.warning("Failed to fetch heads for " + category.getName());
                    if (HeadDB.getInstance().getConfig().getBoolean("fallback", true)) {
                        Log.info("Attempting fallback provider for: " + category.getName());
                        try {
                            // If the original fails and fallback is enabled, fetch from static archive
                            results = gather("https://heads.pages.dev/archive/" + category.getName() + ".json", category);
                        } catch (IOException | ParseException ex) {
                            Log.error("Failed to fetch heads for " + category.getName() + "! (OF)"); // OF = Original-Fallback, both failed
                            Log.error(ex);
                        }
                    }
                }

                updated = System.nanoTime();
                result.put(category, results);
            }

            resultSet.accept(result);
        });
    }

    /**
     * Fetches and gathers the heads from the url.
     * For internal use only!
     *
     * @param url The url
     * @param category The category of the heads
     * @return List of heads for that category
     * @throws IOException error
     * @throws ParseException error
     */
    protected List<Head> gather(String url, Category category) throws IOException, ParseException {
        long start = System.currentTimeMillis();
        List<Head> headList = new ArrayList<>();
        JSONParser parser = new JSONParser();
        JSONArray array = (JSONArray) parser.parse(fetch(url));
        for (Object o : array) {
            JSONObject obj = (JSONObject) o;
            String rawUUID = obj.get("uuid").toString();

            UUID uuid;
            if (Utils.validateUniqueId(rawUUID)) {
                uuid = UUID.fromString(rawUUID);
            } else {
                uuid = UUID.randomUUID();
            }

            Head head = new Head(nextId++)
                    .name(obj.get("name").toString())
                    .uniqueId(uuid)
                    .value(obj.get("value").toString())
                    .tags(obj.get("tags") != null ? obj.get("tags").toString() : "None")
                    .category(category);

            headList.add(head);
        }

        long elapsed = (System.currentTimeMillis() - start);
        Log.debug(category.getName() + " -> Done! Time: " + elapsed + "ms (" + TimeUnit.MILLISECONDS.toSeconds(elapsed) + "s)");
        return headList;
    }

    /**
     * Fetches heads from the url.
     * For internal use only!
     *
     * @param url The url
     * @return JSON-string response
     * @throws IOException error
     */
    protected String fetch(String url) throws IOException {
        String line;
        StringBuilder response = new StringBuilder();

        URLConnection connection = new URL(url).openConnection();
        connection.setConnectTimeout(timeout);
        connection.setRequestProperty("User-Agent", plugin.getName() + "-DatabaseUpdater");
        BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
        while ((line = in.readLine()) != null) {
            response.append(line);
        }

        return response.toString();
    }

    public void update(Consumer<Map<Category, List<Head>>> result) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, task -> getHeadsNoCache(headsList -> {
            if (headsList == null) {
                Log.error("[" + plugin.getName() + "] Failed to update database! Check above for any errors.");
                result.accept(null);
                return;
            }

            heads.clear();
            heads.putAll(headsList);
            result.accept(heads);
            Bukkit.getPluginManager().callEvent(new DatabaseUpdateEvent(this, heads));
        }));
    }

    /**
     * Get the last time the database was updated.
     *
     * @return Last update in seconds
     */
    public long getLastUpdate() {
        long now = System.nanoTime();
        long elapsed = now - updated;
        return TimeUnit.NANOSECONDS.toSeconds(elapsed);
    }

    /**
     * Checks if the update is past the refresh time
     *
     * @return Whether the update is old
     */
    public boolean isLastUpdateOld() {
        return getLastUpdate() >= refresh;
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    public int getTimeout() {
        return timeout;
    }

    public long getRefresh() {
        return refresh;
    }

    public void setRefresh(long refresh) {
        this.refresh = refresh;
    }

    public JavaPlugin getPlugin() {
        return plugin;
    }

}
