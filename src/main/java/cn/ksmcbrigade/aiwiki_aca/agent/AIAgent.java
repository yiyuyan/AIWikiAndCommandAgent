package cn.ksmcbrigade.aiwiki_aca.agent;

import java.lang.instrument.Instrumentation;

public class AIAgent {
    public static void agentmain(String args, Instrumentation inst){
        System.getProperties().put("inst",inst);
    }

    public static void premain(String args,Instrumentation inst){
        agentmain(args, inst);
    }
}
