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
import fr.samflix.vaniametrics.api.Joueur;
import fr.samflix.vaniametrics.api.PlayerSeries;

/**
 * Les quêtes — SANS TOUCHER À LA BASE DE DONNÉES.
 *
 * <p>C'ÉTAIT LE POINT À TRANCHER. L'état de BetonQuest vit dans MySQL, et la voie évidente était
 * d'y faire un {@code SELECT tag, COUNT(*)}. Elle aurait couplé l'exportateur au schéma d'un
 * plugin qu'on ne maîtrise pas, imposé une connexion JDBC, et fait dépendre le TPS de la santé
 * d'une base. Vérifié dans le jar : BetonQuest émet de VRAIS événements Bukkit —
 * {@link PlayerTagAddEvent}, {@link PlayerUpdatePointEvent}, {@link PlayerJournalAddEvent} — qui
 * portent le tag, la catégorie, le compte et le profil. Il n'y a donc rien à interroger : il suffit
 * d'écouter.
 *
 * <p>CE QU'ON PERD, ET IL FAUT LE SAVOIR : un compteur repart de zéro au redémarrage du serveur.
 * « Combien ont trouvé ce crâne depuis toujours » n'est plus une question que ce collecteur sait
 * poser — {@code increase()} sur une fenêtre, oui ; le cumul de l'histoire, non. Le détail par
 * joueur comble une partie du trou : tant qu'un joueur est connecté, son avancement est publié en
 * ABSOLU, et il ne peut de toute façon pas avancer hors ligne.
 */
public final class BetonQuestCollector implements Collector, Listener {

	private final Config config;

	private Counter tagsAjoutes;
	private Counter tagsRetires;
	private Counter entreesJournal;
	private Counter conversations;
	private Gauge pointsJoueur;
	private Gauge tagsJoueur;
	private PlayerSeries series;

	/**
	 * L'avancement des joueurs connectés, tenu à jour par les événements.
	 *
	 * <p>Indexé par IDENTIFIANT, pas par pseudonyme : celui-ci peut changer en cours de session,
	 * et l'avancement se retrouverait alors sur deux clés pour un seul joueur.
	 */
	private final Map<String, Map<String, Integer>> points = new ConcurrentHashMap<>();
	private final Map<String, Integer> tags = new ConcurrentHashMap<>();

	public BetonQuestCollector(Config config) {
		this.config = config;
	}

	@Override
	public String nom() {
		return "betonquest";
	}

	@Override
	public String origine() {
		return "BetonQuest";
	}

	@Override
	public void declarer(MetricRegistry r) {
		tagsAjoutes = r.counter("quest_tags_total",
				"Tags posés depuis le démarrage. Pour la chasse aux crânes, tag = trouve_<clé> : "
						+ "c'est LA métrique des trouvailles.",
				"tag");
		tagsRetires = r.counter("quest_tags_removed_total",
				"Tags retirés. Une réinitialisation d'avancement se voit ici.", "tag");
		entreesJournal = r.counter("quest_journal_entries_total",
				"Entrées de journal écrites.");
		conversations = r.counter("quest_conversations_total",
				"Conversations engagées avec un PNJ.");
		// « player » ET « uuid » : le pseudonyme pour lire, l'identifiant pour suivre. Voir Joueur.
		pointsJoueur = r.gauge("quest_player_points",
				"Points de quête d'un joueur connecté, par catégorie. Pour la chasse, "
						+ "category = cranes donne le nombre de crânes trouvés.",
				"player", "uuid", "category");
		tagsJoueur = r.gauge("quest_player_tags",
				"Tags posés sur un joueur DEPUIS SA CONNEXION. Ce n'est pas son total : le "
						+ "collecteur ne lit pas la base, il n'a vu que ce qui s'est passé sous "
						+ "ses yeux.",
				"player", "uuid");
		series = new PlayerSeries(r, config);
	}

	@Override
	public void relever(MetricRegistry r) {
		var connectes = Bukkit.getOnlinePlayers().stream()
				.map(j -> Joueur.de(j.getUniqueId(), j.getName()))
				.toList();
		for (Joueur qui : series.retenir(connectes, pointsJoueur, tagsJoueur)) {
			Map<String, Integer> p = points.get(qui.uuid());
			if (p != null) {
				p.forEach((categorie, valeur) -> pointsJoueur.set(valeur, qui.etiquettes(categorie)));
			}
			Integer t = tags.get(qui.uuid());
			if (t != null) {
				tagsJoueur.set(t, qui.etiquettes());
			}
		}
	}

	// ------------------------------------------------------------------ événements
	//
	// MONITOR partout : on observe, on ne décide de rien. Ces événements ne sont pas annulables
	// chez BetonQuest, mais la priorité reste la bonne façon de dire qu'on est un témoin.

	@EventHandler(priority = EventPriority.MONITOR)
	public void onTagAdd(PlayerTagAddEvent e) {
		String tag = normaliser(e.getTag());
		tagsAjoutes.inc(tag);
		String id = identifiantDe(e.getProfile());
		if (id != null) {
			tags.merge(id, 1, Integer::sum);
		}
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onTagRemove(PlayerTagRemoveEvent e) {
		tagsRetires.inc(normaliser(e.getTag()));
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onPoint(PlayerUpdatePointEvent e) {
		String id = identifiantDe(e.getProfile());
		if (id != null) {
			points.computeIfAbsent(id, k -> new HashMap<>())
					.put(normaliser(e.getCategory()), e.getNewCount());
		}
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onJournal(PlayerJournalAddEvent e) {
		entreesJournal.inc();
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onConversation(PlayerConversationStartEvent e) {
		conversations.inc();
	}

	/**
	 * L'état par joueur meurt avec la session.
	 *
	 * <p>Sans ça, la carte grossirait indéfiniment — un joueur par connexion, pour toujours. Le
	 * plafond de {@link PlayerSeries} borne ce qui est PUBLIÉ ; ceci borne ce qui est RETENU, et
	 * les deux sont nécessaires.
	 */
	@EventHandler(priority = EventPriority.MONITOR)
	public void onQuit(PlayerQuitEvent e) {
		String id = e.getPlayer().getUniqueId().toString();
		points.remove(id);
		tags.remove(id);
	}

	/** L'identifiant derrière un profil BetonQuest, ou {@code null} si le joueur est parti. */
	private static String identifiantDe(Profile profil) {
		var joueur = profil.getPlayer();
		return joueur == null ? null : joueur.getUniqueId().toString();
	}

	/**
	 * Retire le préfixe de paquet : « cranes>trouve_guetteur » devient « trouve_guetteur ».
	 *
	 * <p>DEUX SÉPARATEURS, et c'est ce qui manquait. Seul le point était traité, alors que
	 * BetonQuest 3 écrit ses chemins avec un chevron. La catégorie de la chasse aux crânes
	 * ressortait donc telle quelle et Prometheus a stocké pendant des jours :
	 *
	 * <pre>mc_quest_player_points{category="cranes&gt;cranes"}</pre>
	 *
	 * <p>Une étiquette parfaitement crédible, qui n'a fait tousser personne — c'est en relisant
	 * les séries pour une tout autre raison qu'elle a sauté aux yeux.
	 */
	private static String normaliser(String brut) {
		if (brut == null) {
			return "unknown";
		}
		int coupe = Math.max(brut.lastIndexOf('.'), brut.lastIndexOf('>'));
		return (coupe < 0 ? brut : brut.substring(coupe + 1)).toLowerCase(Locale.ROOT);
	}
}
