package cn.ksmcbrigade.aiwiki_aca.util.agent;


import cn.ksmcbrigade.aiwiki_aca.McChatbot;
import cn.ksmcbrigade.aiwiki_aca.transformers.ClassByteGetter;
import net.neoforged.fml.loading.FMLLoader;

import javax.annotation.Nullable;
import java.io.File;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;


import java.lang.instrument.UnmodifiableClassException;
import java.util.ArrayList;
import java.util.List;

public class InstUtils {

    public static List<ClassFileTransformerInfo> getTransformersInfo(Instrumentation inst, boolean retransformable){
        ArrayList<ClassFileTransformerInfo> transformerArrayList = new ArrayList<>();
        Object transformerManager;
        if(retransformable){
            transformerManager = UnsafeUtils.getFieldValue(inst,"mRetransfomableTransformerManager", Object.class);
            if(transformerManager==null){
                McChatbot.LOGGER.warn("The retransformable transformer manager of {} is null.",inst);
                return transformerArrayList;
            }
        }
        else{
            transformerManager = UnsafeUtils.getFieldValue(inst,"mTransformerManager", Object.class);
        }

        Object[] transformerInfo = UnsafeUtils.getFieldValue(transformerManager,"mTransformerList", Object[].class);
        if(transformerInfo==null){
            McChatbot.LOGGER.warn("The transformerInfo of {} is null.Retransformable: {}",inst,retransformable);
            return transformerArrayList;
        }

        for (Object o : transformerInfo) {
            transformerArrayList.add(
                    new ClassFileTransformerInfo(
                            UnsafeUtils.getFieldValue(o,"mTransformer", ClassFileTransformer.class),
                            UnsafeUtils.getFieldValue(o,"mPrefix", String.class)
                    )
            );
        }

        return transformerArrayList;
    }

    public static List<ClassFileTransformer> getTransformers(Instrumentation inst, boolean retransformable){
        return getTransformersInfo(inst,retransformable).stream().map((info)->info.classFileTransformer).toList();
    }

    public static byte[] getClassBytes(Instrumentation inst,Class<?> clazz) throws UnmodifiableClassException {
        ClassByteGetter getter = new ClassByteGetter(clazz);
        inst.addTransformer(getter,true);
        inst.retransformClasses(clazz);
        while(getter.bytes==ClassByteGetter.WAITING_BYTES) Thread.yield();
        inst.removeTransformer(getter);
        return getter.bytes;
    }

    @Nullable
    public static Instrumentation getInst(){
        return (Instrumentation) System.getProperties().getOrDefault("inst",null);
    }

    public static void install() {
        if(FMLLoader.getCurrent().isProduction()){
            UnsafeUtils.loadAgent(UnsafeUtils.getJarPath(InstUtils.class));
        }
        else{
            UnsafeUtils.loadAgent(new File(System.getProperty("user.dir")).getParentFile().toPath().resolve("build").resolve("libs").resolve(McChatbot.BUILT_JAR).toAbsolutePath().toString());
        }

        if(getInst()==null) throw new RuntimeException("Failed to install agent.");
        McChatbot.LOGGER.info("Install JavaAgent Successfully. Instrumentation: {}",getInst());
    }

    public record ClassFileTransformerInfo(ClassFileTransformer classFileTransformer,String prefix){}
}