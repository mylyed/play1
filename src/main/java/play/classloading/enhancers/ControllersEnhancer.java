package play.classloading.enhancers;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Stack;

import javassist.CannotCompileException;
import javassist.CtClass;
import javassist.CtField;
import javassist.CtMethod;
import javassist.Modifier;
import javassist.NotFoundException;
import javassist.bytecode.annotation.Annotation;
import javassist.expr.ExprEditor;
import javassist.expr.FieldAccess;
import javassist.expr.Handler;
import play.Logger;
import play.classloading.ApplicationClasses.ApplicationClass;
import play.exceptions.UnexpectedException;

/**
 * Enhance controllers classes.
 * <p>
 * 该类对controller进行增强。
 * <p>
 * 我们在action中，可以直接调用一些static字段，如request.getXxx(), params.get(“xxx”)。在调用时，我们知道它们是“线程安全”的，但是，为什么？要知道它们是static的，是可以被多线程同时访问的。
 * <p>
 * 原来ControllersEnhancer对它们进行的替换。当我们调用"params"’、 "request"、 "response"、 "session"、 "params"、 "renderArgs"、 "routeArgs"、 "validation"、 "inbound"、 "outbound"、 "flash" 这些参数时，它们都会被替换为：
 * <p>
 * Xxx.current()
 * 这样的形式，从而从ThreadLocal中获取当前线程绑定的值。
 * <p>
 * 该类还进行了其它增强，比如当一个action被调用时要进行判断，是当作一个普通的方法调用，还是返回一个redirect的response给客户端。
 * <p>
 * 另外，play中render()等方法，会以抛出异常的方式判断模板层，为了保证该异常不会被其它类捕获，在这里也进行了检查和屏蔽。
 */
public class ControllersEnhancer extends Enhancer {

    public static final ThreadLocal<Stack<String>> currentAction = new ThreadLocal<>();

    @Override
    public void enhanceThisClass(final ApplicationClass applicationClass) throws Exception {
        if (isAnon(applicationClass)) {
            return;
        }

        CtClass ctClass = makeClass(applicationClass);

        if (!ctClass.subtypeOf(classPool.get(ControllerSupport.class.getName()))) {
            return;
        }

        for (final CtMethod ctMethod : ctClass.getDeclaredMethods()) {

            // Threaded access
            ctMethod.instrument(new ExprEditor() {

                @Override
                public void edit(FieldAccess fieldAccess) throws CannotCompileException {
                    try {
                        if (isThreadedFieldAccess(fieldAccess.getField())) {
                            if (fieldAccess.isReader()) {
                                fieldAccess.replace("$_ = ($r)play.utils.Java.invokeStatic($type, \"current\");");
                            }
                        }
                    } catch (Exception e) {
                        Logger.error(e, "Error in ControllersEnhancer. %s.%s has not been properly enhanced (fieldAccess %s).", applicationClass.name, ctMethod.getName(), fieldAccess);
                        throw new UnexpectedException(e);
                    }
                }
            });

            // Auto-redirect
            boolean isHandler = false;
            for (Annotation a : getAnnotations(ctMethod).getAnnotations()) {
                if (a.getTypeName().startsWith("play.mvc.")) {
                    isHandler = true;
                    break;
                }
                if (a.getTypeName().endsWith("$ByPass")) {
                    isHandler = true;
                    break;
                }
            }

            // Perhaps it is a scala-generated method ?
            if (ctMethod.getName().contains("$")) {
                isHandler = true;
            } else {
                if (ctClass.getName().endsWith("$") && ctMethod.getParameterTypes().length == 0) {
                    try {
                        ctClass.getField(ctMethod.getName());
                        isHandler = true;
                    } catch (NotFoundException e) {
                        // ok
                    }
                }
            }

            if (isScalaObject(ctClass)) {

                // Auto reverse -->
                if (Modifier.isPublic(ctMethod.getModifiers()) && ((ctClass.getName().endsWith("$") && !ctMethod.getName().contains("$default$"))) && !isHandler) {
                    try {
                        ctMethod.insertBefore(
                                "if(play.mvc.Controller._currentReverse.get() != null) {"
                                        + "play.mvc.Controller.redirect(\"" + ctClass.getName().replace("$", "") + "." + ctMethod.getName() + "\", $args);"
                                        + generateValidReturnStatement(ctMethod.getReturnType())
                                        + "}");

                        ctMethod.insertBefore(
                                "((java.util.Stack)play.classloading.enhancers.ControllersEnhancer.currentAction.get()).push(\"" + ctClass.getName().replace("$", "") + "." + ctMethod.getName() + "\");");

                        ctMethod.insertAfter(
                                "((java.util.Stack)play.classloading.enhancers.ControllersEnhancer.currentAction.get()).pop();", true);

                    } catch (Exception e) {
                        Logger.error(e, "Error in ControllersEnhancer. %s.%s has not been properly enhanced (auto-reverse).", applicationClass.name, ctMethod.getName());
                        throw new UnexpectedException(e);
                    }
                }

            } else {

                // Auto redirect -->
                if (Modifier.isPublic(ctMethod.getModifiers()) && Modifier.isStatic(ctMethod.getModifiers()) && ctMethod.getReturnType().equals(CtClass.voidType) && !isHandler) {
                    try {
                        ctMethod.insertBefore(
                                "if(!play.classloading.enhancers.ControllersEnhancer.ControllerInstrumentation.isActionCallAllowed()) {"
                                        + "play.mvc.Controller.redirect(\"" + ctClass.getName().replace("$", "") + "." + ctMethod.getName() + "\", $args);"
                                        + generateValidReturnStatement(ctMethod.getReturnType()) + "}"
                                        + "play.classloading.enhancers.ControllersEnhancer.ControllerInstrumentation.stopActionCall();");

                    } catch (Exception e) {
                        Logger.error(e, "Error in ControllersEnhancer. %s.%s has not been properly enhanced (auto-redirect).", applicationClass.name, ctMethod.getName());
                        throw new UnexpectedException(e);
                    }
                }

            }

            // Enhance global catch to avoid potential unwanted catching of play.mvc.results.Result
            ctMethod.instrument(new ExprEditor() {

                @Override
                public void edit(Handler handler) throws CannotCompileException {
                    StringBuilder code = new StringBuilder();
                    try {
                        code.append("if($1 instanceof play.mvc.results.Result || $1 instanceof play.Invoker.Suspend) throw $1;");
                        handler.insertBefore(code.toString());
                    } catch (NullPointerException e) {
                        // TODO: finally clause ?
                        // There are no $1 in finally statements in javassist
                    }
                }
            });

        }

        // Done.
        applicationClass.enhancedByteCode = ctClass.toBytecode();

        ctClass.defrost();

    }

    /**
     * Mark class that need controller enhancement
     */
    public static interface ControllerSupport {
    }

    /**
     * Check if a field must be translated to a 'thread safe field'
     */
    static boolean isThreadedFieldAccess(CtField field) {
        if (field.getDeclaringClass().getName().equals("play.mvc.Controller") || field.getDeclaringClass().getName().equals("play.mvc.WebSocketController")) {
            return field.getName().equals("params")
                    || field.getName().equals("request")
                    || field.getName().equals("response")
                    || field.getName().equals("session")
                    || field.getName().equals("params")
                    || field.getName().equals("renderArgs")
                    || field.getName().equals("routeArgs")
                    || field.getName().equals("validation")
                    || field.getName().equals("inbound")
                    || field.getName().equals("outbound")
                    || field.getName().equals("flash");
        }
        return false;
    }

    /**
     * Runtime part needed by the instrumentation
     */
    public static class ControllerInstrumentation {

        public static boolean isActionCallAllowed() {
            return allow.get();
        }

        public static void initActionCall() {
            allow.set(true);
        }

        public static void stopActionCall() {
            allow.set(false);
        }

        static final ThreadLocal<Boolean> allow = new ThreadLocal<>();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface ByPass {
    }

    static String generateValidReturnStatement(CtClass type) {
        if (type.equals(CtClass.voidType)) {
            return "return;";
        }
        if (type.equals(CtClass.booleanType)) {
            return "return false;";
        }
        if (type.equals(CtClass.charType)) {
            return "return '';";
        }
        if (type.equals(CtClass.byteType)) {
            return "return (byte)0;";
        }
        if (type.equals(CtClass.doubleType)) {
            return "return (double)0;";
        }
        if (type.equals(CtClass.floatType)) {
            return "return (float)0;";
        }
        if (type.equals(CtClass.intType)) {
            return "return (int)0;";
        }
        if (type.equals(CtClass.longType)) {
            return "return (long)0;";
        }
        if (type.equals(CtClass.shortType)) {
            return "return (short)0;";
        }
        return "return null;";
    }
}
