package com.huidu.farmersdelight.compat;

import com.huidu.farmersdelight.i18n.I18n;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Optional public-API bridge, with no HandbookCore classes needed to load FarmersDelight. */
public final class HandbookHook implements Listener, AutoCloseable {
    private static final String CORE = "HandbookCore";
    private static final String SERVICE = "com.example.handbookcore.api.HandbookService";
    private static final String RESOURCE = "handbook/farmersdelight.yml";
    private final JavaPlugin plugin;
    private boolean running;
    private Binding binding;

    public HandbookHook(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        reload();
    }

    public synchronized void reload() {
        if (!running) return;
        Plugin core = plugin.getServer().getPluginManager().getPlugin(CORE);
        if (!plugin.isEnabled() || !plugin.getConfig().getBoolean("handbook.enabled", true)
                || core == null || !core.isEnabled()) {
            disconnect();
            return;
        }
        try {
            ClassLoader loader = core.getClass().getClassLoader();
            Class<?> serviceType = binding != null && binding.core() == core ? binding.serviceType()
                    : Class.forName(SERVICE, true, loader);
            Object service = plugin.getServer().getServicesManager().load(serviceType);
            if (service == null) {
                disconnect();
                return;
            }
            Binding replacement = binding;
            if (replacement == null || replacement.service() != service) {
                // HandbookCore's API targets Java 25; reflecting its public API keeps our Java 21 build independent.
                // Resolve once for this service, and discard the binding when the service goes away.
                HandbookContent.Definition definition = new HandbookContent(loader).read(plugin.getResource(RESOURCE));
                replacement = new Binding(core, serviceType, service,
                        serviceType.getMethod("replace", Plugin.class, definition.contribution().getClass()),
                        serviceType.getMethod("unregisterAll", Plugin.class), definition);
            }
            // The core publishes atomically: invalid content or an ID conflict preserves the existing guide.
            replacement.replace().invoke(service, plugin, replacement.definition().contribution());
            Binding previous = binding;
            binding = replacement;
            if (previous != null && previous.service() != service) unregister(previous);
            if (previous == null) {
                I18n.logDetail("startup", "plugin.handbook_registered", "count", replacement.definition().entryCount());
            }
        } catch (ReflectiveOperationException | IOException | InvalidConfigurationException | RuntimeException | LinkageError error) {
            I18n.logWarning("plugin.handbook_register_failed", "error", reason(error));
        }
    }

    @EventHandler
    public void onServiceRegister(ServiceRegisterEvent event) {
        if (event.getProvider().getService().getName().equals(SERVICE)) reload();
    }

    @EventHandler
    public synchronized void onServiceUnregister(ServiceUnregisterEvent event) {
        if (binding != null && event.getProvider().getProvider() == binding.service()) disconnect();
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if (event.getPlugin().getName().equals(CORE)) reload();
    }

    @EventHandler
    public synchronized void onPluginDisable(PluginDisableEvent event) {
        if (event.getPlugin().getName().equals(CORE)) disconnect();
    }

    @Override
    public synchronized void close() {
        running = false;
        HandlerList.unregisterAll(this);
        disconnect();
    }

    private void disconnect() {
        Binding previous = binding;
        binding = null;
        if (previous != null) unregister(previous);
    }

    private void unregister(Binding previous) {
        try {
            previous.unregister().invoke(previous.service(), plugin);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            I18n.logWarning("plugin.handbook_unregister_failed", "error", reason(error));
        }
    }

    private static String reason(Throwable error) {
        Throwable cause = error instanceof InvocationTargetException invocation && invocation.getCause() != null
                ? invocation.getCause() : error;
        return cause.toString();
    }

    private record Binding(Plugin core, Class<?> serviceType, Object service, Method replace,
                           Method unregister, HandbookContent.Definition definition) { }
}
