package cn.ksmcbrigade.aiwiki_aca;

import cn.ksmcbrigade.aiwiki_aca.events.ChatListener;
import cn.ksmcbrigade.aiwiki_aca.util.agent.CompilerUtils;
import cn.ksmcbrigade.aiwiki_aca.util.agent.InstUtils;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.instrument.ClassFileTransformer;
import java.util.Objects;
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

        /* debug CompilerUtils
        try {
            LOGGER.info("sources for title screen: {}", CompilerUtils.getSource(TitleScreen.class));
        } catch (Throwable e) {
            LOGGER.error("Failed to get sources",e);
        }*/


        /* debug CompileUtils
        try {
            ClassFileTransformer transformer = (ClassFileTransformer) CompilerUtils.compileSingleSourceIntoClassInstanceWithoutArgs("cn.ksmcbrigade.aiwiki_aca.TestTransformer", """
                package cn.ksmcbrigade.aiwiki_aca;
                
                 import org.objectweb.asm.*;
                 import java.lang.instrument.ClassFileTransformer;
                 import java.security.ProtectionDomain;
                
                 public class TestTransformer implements ClassFileTransformer {
                
                     @Override
                     public byte[] transform(ClassLoader loader,
                                             String className,
                                             Class<?> classBeingRedefined,
                                             ProtectionDomain protectionDomain,
                                             byte[] classfileBuffer) {
                         System.out.println("Test transforming class: " + className);
                         return null;
                     }
                 }
                """, McChatbot.class);
            Objects.requireNonNull(InstUtils.getInst()).addTransformer(transformer, true);
            InstUtils.getInst().retransformClasses(Minecraft.class);
            InstUtils.getInst().removeTransformer(transformer);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }*/
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
