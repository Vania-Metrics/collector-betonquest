package fr.samflix.vaniametrics.module.betonquest;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import fr.samflix.vaniametrics.api.VaniaMetrics;
import fr.samflix.vaniametrics.api.VaniaMetricsProvider;

/**
 * BetonQuest quest metrics.
 *
 * <p>This module is a listener as much as a collector: most of its work happens when an event
 * arrives, not when Prometheus scrapes. It registers on both sides — Bukkit's event registry and
 * VaniaMetrics' — and unregisters from both.
 */
public final class BetonQuestPaper extends JavaPlugin {

	private BetonQuestCollector collector;

	@Override
	public void onEnable() {
		VaniaMetrics metrics = VaniaMetricsProvider.get();
		collector = new BetonQuestCollector(metrics.config());
		metrics.register(collector);
		Bukkit.getPluginManager().registerEvents(collector, this);
	}

	@Override
	public void onDisable() {
		if (collector != null) {
			VaniaMetricsProvider.find().ifPresent(m -> m.unregister(collector));
		}
	}
}
