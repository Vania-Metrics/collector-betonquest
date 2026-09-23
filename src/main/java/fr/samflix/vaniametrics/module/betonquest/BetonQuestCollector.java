package fr.samflix.vaniametrics.module.betonquest;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import org.betonquest.betonquest.api.bukkit.event.PlayerConversationStartEvent;
import org.betonquest.betonquest.api.bukkit.event.PlayerJournalAddEvent;
import org.betonquest.betonquest.api.bukkit.event.PlayerTagAddEvent;
import org.betonquest.betonquest.api.bukkit.event.PlayerTagRemoveEvent;
import org.betonquest.betonquest.api.bukkit.event.PlayerUpdatePointEvent;
import org.betonquest.betonquest.api.profile.Profile;

import fr.samflix.vaniametrics.api.Collector;
import fr.samflix.vaniametrics.api.Config;
import fr.samflix.vaniametrics.api.Counter;
import fr.samflix.vaniametrics.api.Gauge;
import fr.samflix.vaniametrics.api.MetricRegistry;
import fr.samflix.vaniametrics.api.PlayerRef;
import fr.samflix.vaniametrics.api.PlayerSeries;

/**
 * Quest progress, without touching the database.
 *
 * <p>BetonQuest keeps its state in MySQL, but a {@code SELECT tag, COUNT(*)} would couple this
 * exporter to a schema it doesn't own, require a JDBC connection, and make TPS depend on a
 * database's health. BetonQuest also emits real Bukkit events — {@link PlayerTagAddEvent},
 * {@link PlayerUpdatePointEvent}, {@link PlayerJournalAddEvent} — carrying the tag, category,
 * count and profile, so there is nothing to query: listening is enough.
 *
 * <p>The trade-off: counters reset on server restart, so "how many players have ever found this
 * skull" is not a question this collector can answer — {@code increase()} over a window, yes;
 * lifetime totals, no. The per-player gauges cover part of that gap: while a player is online,
 * their progress is published as an absolute value, and they can't progress while offline anyway.
 */
public final class BetonQuestCollector implements Collector, Listener {

	private final Config config;

	private Counter tagsAdded;
	private Counter tagsRemoved;
	private Counter journalEntries;
	private Counter conversations;
	private Gauge playerPoints;
	private Gauge playerTags;
	private PlayerSeries series;

	/**
	 * Progress of online players, kept up to date by events.
	 *
	 * <p>Indexed by UUID, not by name: a name can change mid-session, which would otherwise split
	 * one player's progress across two keys.
	 */
	private final Map<String, Map<String, Integer>> points = new ConcurrentHashMap<>();
	private final Map<String, Integer> tags = new ConcurrentHashMap<>();

	public BetonQuestCollector(Config config) {
		this.config = config;
	}

	@Override
	public String name() {
		return "betonquest";
	}

	@Override
	public String source() {
		return "BetonQuest";
	}

	@Override
	public void declare(MetricRegistry r) {
		tagsAdded = r.counter("quest_tags_total",
				"Tags added since startup. For skull hunts, tag = found_<key>: this is THE "
						+ "metric for finds.",
				"tag");
		tagsRemoved = r.counter("quest_tags_removed_total",
				"Tags removed. A progress reset shows up here.", "tag");
		journalEntries = r.counter("quest_journal_entries_total",
				"Journal entries written.");
		conversations = r.counter("quest_conversations_total",
				"Conversations started with an NPC.");
		// "player" AND "uuid": the name to read, the uuid to track. See PlayerRef.
		playerPoints = r.gauge("quest_player_points",
				"Quest points for an online player, by category. For a skull hunt, "
						+ "category = skulls gives the number of skulls found.",
				"player", "uuid", "category");
		playerTags = r.gauge("quest_player_tags",
				"Tags added to a player SINCE THEY CONNECTED. Not their lifetime total: the "
						+ "collector doesn't read the database, only what happened while it was "
						+ "watching.",
				"player", "uuid");
		series = new PlayerSeries(r, config);
	}

	@Override
	public void collect(MetricRegistry r) {
		var online = Bukkit.getOnlinePlayers().stream()
				.map(p -> PlayerRef.of(p.getUniqueId(), p.getName()))
				.toList();
		for (PlayerRef ref : series.select(online, playerPoints, playerTags)) {
			Map<String, Integer> p = points.get(ref.uuid());
			if (p != null) {
				p.forEach((category, value) -> playerPoints.set(value, ref.labels(category)));
			}
			Integer t = tags.get(ref.uuid());
			if (t != null) {
				playerTags.set(t, ref.labels());
			}
		}
	}

	// ------------------------------------------------------------------ events
	//
	// MONITOR everywhere: this collector only observes, never decides. BetonQuest's events aren't
	// cancellable, but MONITOR is still the right way to declare "just a witness" intent.

	@EventHandler(priority = EventPriority.MONITOR)
	public void onTagAdd(PlayerTagAddEvent e) {
		String tag = normalize(e.getTag());
		tagsAdded.inc(tag);
		String id = uuidOf(e.getProfile());
		if (id != null) {
			tags.merge(id, 1, Integer::sum);
		}
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onTagRemove(PlayerTagRemoveEvent e) {
		tagsRemoved.inc(normalize(e.getTag()));
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onPoint(PlayerUpdatePointEvent e) {
		String id = uuidOf(e.getProfile());
		if (id != null) {
			points.computeIfAbsent(id, k -> new HashMap<>())
					.put(normalize(e.getCategory()), e.getNewCount());
		}
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onJournal(PlayerJournalAddEvent e) {
		journalEntries.inc();
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onConversation(PlayerConversationStartEvent e) {
		conversations.inc();
	}

	/**
	 * Per-player state dies with the session.
	 *
	 * <p>Otherwise the map would grow forever, one entry per connection. {@link PlayerSeries}
	 * caps what gets PUBLISHED; this caps what gets RETAINED, and both are needed.
	 */
	@EventHandler(priority = EventPriority.MONITOR)
	public void onQuit(PlayerQuitEvent e) {
		String id = e.getPlayer().getUniqueId().toString();
		points.remove(id);
		tags.remove(id);
	}

	/** The uuid behind a BetonQuest profile, or {@code null} if the player has left. */
	private static String uuidOf(Profile profile) {
		var player = profile.getPlayer();
		return player == null ? null : player.getUniqueId().toString();
	}

	/**
	 * Strips the package prefix: "skulls&gt;found_watcher" becomes "found_watcher".
	 *
	 * <p>Two separators need handling: only the dot was, while BetonQuest 3 writes paths with a
	 * chevron. The skull hunt category was left untouched and Prometheus stored, for days:
	 *
	 * <pre>mc_quest_player_points{category="skulls&gt;skulls"}</pre>
	 *
	 * <p>A perfectly plausible label that no one noticed — it surfaced only while reviewing the
	 * series for an unrelated reason.
	 */
	private static String normalize(String raw) {
		if (raw == null) {
			return "unknown";
		}
		int cut = Math.max(raw.lastIndexOf('.'), raw.lastIndexOf('>'));
		return (cut < 0 ? raw : raw.substring(cut + 1)).toLowerCase(Locale.ROOT);
	}
}
