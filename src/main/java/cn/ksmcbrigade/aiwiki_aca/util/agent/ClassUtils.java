package cn.ksmcbrigade.aiwiki_aca.util.agent;

import net.neoforged.fml.loading.FMLLoader;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public class ClassUtils {
    public static Set<String> getAllPackages(String module){
        return FMLLoader.getCurrent().getGameLayer().findModule(module).orElseThrow(()->new RuntimeException("Cannot find the module.")).getPackages();
    }

    public static Set<String> getAllLoadedClasses(){
        if(InstUtils.getInst()==null) throw new RuntimeException("The inst is null.Failed to get inst.");
        return Arrays.stream(InstUtils.getInst().getAllLoadedClasses()).map(Class::getName).collect(Collectors.toSet());
    }

    public static Set<String> getAllLoadedClasses(String prefix){
        if(InstUtils.getInst()==null) throw new RuntimeException("The inst is null.Failed to get inst.");
        return Arrays.stream(InstUtils.getInst().getAllLoadedClasses()).map(Class::getName).filter((f)->f.startsWith(prefix)).collect(Collectors.toSet());
    }
}
