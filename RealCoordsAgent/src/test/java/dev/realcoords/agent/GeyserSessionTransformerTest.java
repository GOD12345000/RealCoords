package dev.realcoords.agent;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GeyserSessionTransformerTest {
    private static final String SESSION = "org/geysermc/mcprotocollib/network/Session";
    private static final String LISTENER = "org/geysermc/mcprotocollib/network/event/session/SessionListener";
    private static final String ADD_LISTENER = "(L" + LISTENER + ";)V";

    @Test
    void injectsBridgeBeforeFirstOfExactlyTwoListenerRegistrations() {
        byte[] original = fixture(2);
        byte[] transformed = new GeyserSessionTransformer().transform(
                null, getClass().getClassLoader(),
                "org/geysermc/geyser/session/GeyserSession",
                null, null, original);

        assertNotNull(transformed, "Expected pinned Geyser bytecode shape to be transformed");

        List<String> calls = methodCalls(transformed);
        int bridge = calls.indexOf("dev/realcoords/agent/AgentBridge.beforeFirstListener");
        int firstListener = calls.indexOf(SESSION + ".addListener");
        assertTrue(bridge >= 0, "Bridge callback must be present");
        assertTrue(firstListener > bridge, "Bridge must execute before Geyser's first listener registration");
        assertEquals(2, calls.stream().filter(call -> call.equals(SESSION + ".addListener")).count());
    }

    @Test
    void refusesUnexpectedListenerRegistrationCount() {
        byte[] original = fixture(1);
        byte[] transformed = new GeyserSessionTransformer().transform(
                null, getClass().getClassLoader(),
                "org/geysermc/geyser/session/GeyserSession",
                null, null, original);

        assertNull(transformed, "Unexpected target bytecode must fail closed");
    }

    private static byte[] fixture(int listenerCalls) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "fixture/GeyserSession", null,
                "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "connectDownstream", "()V", null, null);
        method.visitCode();
        for (int i = 0; i < listenerCalls; i++) {
            method.visitInsn(Opcodes.ACONST_NULL);
            method.visitInsn(Opcodes.ACONST_NULL);
            method.visitMethodInsn(Opcodes.INVOKEINTERFACE, SESSION, "addListener", ADD_LISTENER, true);
        }
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(2, 1);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static List<String> methodCalls(byte[] bytecode) {
        List<String> calls = new ArrayList<>();
        new ClassReader(bytecode).accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!"connectDownstream".equals(name)) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name,
                                                String descriptor, boolean isInterface) {
                        if ("beforeFirstListener".equals(name)) {
                            calls.add("dev/realcoords/agent/AgentBridge.beforeFirstListener");
                        } else if ("addListener".equals(name)) {
                            calls.add(owner + ".addListener");
                        }
                    }
                };
            }
        }, 0);
        return calls;
    }
}
