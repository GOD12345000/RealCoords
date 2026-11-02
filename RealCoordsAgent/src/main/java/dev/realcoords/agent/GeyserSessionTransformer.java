package dev.realcoords.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * For the pinned Geyser 2.12.1 source shape, injects a callback immediately before
 * the first downstream Session.addListener(...) call in GeyserSession.connectDownstream().
 *
 * The callback registers RealCoords first; Geyser then registers ClientListener and
 * GeyserSessionAdapter in their original order.
 */
final class GeyserSessionTransformer implements ClassFileTransformer {
    private static final String TARGET_CLASS = "org/geysermc/geyser/session/GeyserSession";
    private static final String TARGET_METHOD = "connectDownstream";
    private static final String TARGET_METHOD_DESCRIPTOR = "()V";
    private static final String LISTENER_DESCRIPTOR =
            "(Lorg/geysermc/mcprotocollib/network/event/session/SessionListener;)V";
    private static final String BRIDGE_CLASS = "dev/realcoords/agent/AgentBridge";
    private static final String BRIDGE_DESCRIPTOR = "(Ljava/lang/Object;Ljava/lang/Object;)V";

    @Override
    public byte[] transform(
            Module module,
            ClassLoader loader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer) {
        if (!TARGET_CLASS.equals(className)) {
            return null;
        }

        AtomicBoolean targetMethodFound = new AtomicBoolean();
        AtomicBoolean hookInjected = new AtomicBoolean();
        AtomicInteger listenerCalls = new AtomicInteger();

        ClassReader reader = new ClassReader(classfileBuffer);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!TARGET_METHOD.equals(name) || !TARGET_METHOD_DESCRIPTOR.equals(descriptor)) {
                    return delegate;
                }

                targetMethodFound.set(true);
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitMethodInsn(
                            int opcode,
                            String owner,
                            String methodName,
                            String methodDescriptor,
                            boolean isInterface) {
                        if ("addListener".equals(methodName)
                                && LISTENER_DESCRIPTOR.equals(methodDescriptor)) {
                            int callNumber = listenerCalls.incrementAndGet();
                            if (callNumber == 1) {
                                /*
                                 * Before the original invokeinterface, the operand stack is:
                                 *   [downstreamSession, listener]
                                 *
                                 * DUP2, POP, ALOAD 0, SWAP leaves that original pair intact
                                 * while passing (this, downstreamSession) to AgentBridge.
                                 */
                                super.visitInsn(Opcodes.DUP2);
                                super.visitInsn(Opcodes.POP);
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitInsn(Opcodes.SWAP);
                                super.visitMethodInsn(
                                        Opcodes.INVOKESTATIC,
                                        BRIDGE_CLASS,
                                        "beforeFirstListener",
                                        BRIDGE_DESCRIPTOR,
                                        false
                                );
                                hookInjected.set(true);
                            }
                        }

                        super.visitMethodInsn(opcode, owner, methodName, methodDescriptor, isInterface);
                    }
                };
            }
        };

        reader.accept(visitor, 0);

        // Fail closed if the pinned method shape changes. The expected source has exactly
        // two listener registrations: ClientListener followed by GeyserSessionAdapter.
        if (!targetMethodFound.get() || !hookInjected.get() || listenerCalls.get() != 2) {
            System.err.println("[RealCoordsAgent] GeyserSession hook was NOT installed: "
                    + "unexpected connectDownstream bytecode shape (methodFound="
                    + targetMethodFound.get() + ", listenerCalls=" + listenerCalls.get() + ").");
            return null;
        }

        AgentBridge.markTransformed();
        System.err.println("[RealCoordsAgent] Verified GeyserSession.connectDownstream hook "
                + "before the first of two listener registrations.");
        return writer.toByteArray();
    }
}
