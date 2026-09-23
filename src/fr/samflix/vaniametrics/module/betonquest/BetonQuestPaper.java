package fr.samflix.vaniametrics.module.betonquest;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import fr.samflix.vaniametrics.api.VaniaMetrics;
import fr.samflix.vaniametrics.api.VaniaMetricsProvider;

/**
 * Métriques des quêtes BetonQuest.
 *
 * <p>Ce module est un ÉCOUTEUR autant qu'un collecteur : l'essentiel de son travail se fait quand
 * l'événement arrive, pas quand Prometheus interroge. Il s'enregistre donc des deux côtés — dans
 * le registre d'événements de Bukkit et dans celui de VaniaMetrics — et se retire des deux.
 */
public final class BetonQuestPaper extends JavaPlugin {

	private BetonQuestCollector collecteur;

	@Override
	public void onEnable() {
		VaniaMetrics metriques = VaniaMetricsProvider.get();
		collecteur = new BetonQuestCollector(metriques.config());
		metriques.enregistrer(collecteur);
		Bukkit.getPluginManager().registerEvents(collecteur, this);
	}

	@Override
	public void onDisable() {
		if (collecteur != null) {
			VaniaMetricsProvider.chercher().ifPresent(m -> m.retirer(collecteur));
		}
	}
}
