package cn.ksmcbrigade.aiwiki_aca;

import cn.ksmcbrigade.aiwiki_aca.events.ChatListener;
import cn.ksmcbrigade.aiwiki_aca.util.agent.InstUtils;
import net.neoforged.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Mod(McChatbot.MOD_ID)
public class McChatbot {
    public static final String MOD_ID = "ai_wiki_aca";
    public static final String BUILT_JAR = "ai_wiki_aca-1.0.jar";
    public static final Logger LOGGER = LogManager.getLogger("AIWikiAndCommand");
    public static final java.util.concurrent.ExecutorService AI_EXECUTOR = java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "AIWikiAndCommand-Worker");
        t.setDaemon(true);
        return t;
    });

    public McChatbot(net.neoforged.bus.api.IEventBus modEventBus, net.neoforged.fml.ModContainer container) {
        cn.ksmcbrigade.aiwiki_aca.Config.register(container);

        // Create external knowledge directory at mod load time
        try {
            var dir = cn.ksmcbrigade.aiwiki_aca.knowledge.KnowledgeManager.getInstance().getKnowledgeDirPublic();
            dir.mkdirs();
            LOGGER.info("External knowledge directory: {}", dir.getAbsolutePath());
        } catch (Exception ignored) {
        }

        var forgeBus = net.neoforged.neoforge.common.NeoForge.EVENT_BUS;
        forgeBus.addListener(this::onServerStarting);
        forgeBus.addListener(this::onRegisterCommands);

        InstUtils.install();
    }

    private void onServerStarting(net.neoforged.neoforge.event.server.ServerStartingEvent event) {
        cn.ksmcbrigade.aiwiki_aca.knowledge.KnowledgeManager.getInstance().load();
        cn.ksmcbrigade.aiwiki_aca.ai.ModelManager.getInstance().refreshModels();

        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SessionCleanup");
            t.setDaemon(true);
            return t;
        }).scheduleAtFixedRate(() -> {
            ChatListener.cleanupStaleSessions(180_000);
        }, 1, 1, TimeUnit.MINUTES);

        LOGGER.info("AIWikiAndCommand initialized.");
    }

    private void onRegisterCommands(net.neoforged.neoforge.event.RegisterCommandsEvent event) {
        cn.ksmcbrigade.aiwiki_aca.commands.ModCommands.register(event.getDispatcher());
    }

    /*
    for future
    @EventBusSubscriber(modid = McChatbot.MOD_ID,value = Dist.CLIENT)
    private static class ClientEvents{
        @SubscribeEvent
        private static void clientSetup(FMLClientSetupEvent event){
            ModList.get().getModContainerById(McChatbot.MOD_ID).orElseThrow().registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        }
    }
    */
}
