package io.github.aw1y2z.sesame.hook;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import io.github.aw1y2z.sesame.entity.RpcEntity;
import io.github.aw1y2z.sesame.util.ClassUtil;
import io.github.aw1y2z.sesame.util.Log;
import io.github.aw1y2z.sesame.util.XHelpers;
import io.github.aw1y2z.sesame.util.compat.XC_LoadPackage;
import io.github.aw1y2z.sesame.util.compat.XC_MethodHook;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import lombok.Getter;

/**
 * Hook 淘宝 App (com.taobao.taobao)，获取 MTOP SDK 调用能力。
 * 淘宝芭芭农场使用 MTOP 协议（mtop.tmall.farm.*），不是支付宝的 RpcBridgeExtension。
 */
public class TaobaoApplicationHook {

    private static final String TAG = "TaobaoHook";
    private static final String TAOBAO_PACKAGE = "com.taobao.taobao";

    @Getter
    private static volatile ClassLoader classLoader = null;

    @Getter
    @SuppressLint("StaticFieldLeak")
    private static volatile Context context = null;

    @Getter
    private static volatile boolean hooked = false;

    @Getter
    private static Handler mainHandler;

    /** MTOP 请求实例（mtopsdk.mtop.intf.Mtop） */
    private static Object mtopInstance = null;

    /** MTOP request 方法引用 */
    private static Method mtopRequestMethod = null;

    public static void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TAOBAO_PACKAGE.equals(lpparam.packageName)) {
            return;
        }
        if (hooked) {
            return;
        }
        classLoader = lpparam.classLoader;
        Log.i(TAG, "Loading Taobao hook, package=" + lpparam.packageName
                + " process=" + lpparam.processName);
        try {
            hookMtop(lpparam.classLoader);
            hooked = true;
        } catch (Throwable t) {
            Log.printStackTrace(TAG, t);
        }
    }

    /**
     * Hook MTOP SDK，获取请求发送能力。
     * 淘宝 MTOP 核心类:
     *   - mtopsdk.mtop.intf.Mtop (MTOP 实例工厂)
     *   - mtopsdk.mtop.domain.MethodEnum (GET/POST)
     *   - com.taobao.tao.remotebusiness.MtopBusiness (高层封装)
     *   - mtopsdk.mtop.intf.MtopBuilder (请求构建器)
     */
    private static void hookMtop(ClassLoader loader) throws Exception {
        // 尝试通过 Mtop.getInstance 获取 MTOP 实例
        Class<?> mtopClass = loader.loadClass("mtopsdk.mtop.intf.Mtop");
        // Mtop.instance("MTOP_ID_DTAOBAO", Context) 或 Mtop.getInstance(context)
        // 先用反射找可用的工厂方法
        Log.i(TAG, "Found Mtop class: " + mtopClass.getName());

        // 保存 Mtop class 引用，后续 requestString 时使用
        mtopClassRef = mtopClass;
        Log.i(TAG, "MTOP class loaded successfully");
    }

    private static Class<?> mtopClassRef = null;

    /**
     * 发送 MTOP 请求（同步，等待回调）。
     *
     * @param apiName  API 名称，如 "mtop.tmall.farm.orchard.main.get"
     * @param version  API 版本，如 "1.0"
     * @param params   JSON 请求参数字符串
     * @return 响应 JSON 字符串，失败返回 null
     */
    public static String requestString(String apiName, String version, String params) {
        if (classLoader == null) {
            Log.i(TAG, "Taobao not hooked yet");
            return null;
        }
        try {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> resultRef = new AtomicReference<>();
            AtomicReference<Throwable> errorRef = new AtomicReference<>();

            // 使用 MtopBuilder 构建请求
            Class<?> mtopClass = classLoader.loadClass("mtopsdk.mtop.intf.Mtop");
            // 获取默认实例
            Object mtopInstance = getMtopInstance(mtopClass);
            if (mtopInstance == null) {
                Log.i(TAG, "Cannot get Mtop instance");
                return null;
            }

            // 构建 MtopBuilder
            Method builderMethod = mtopInstance.getClass().getMethod("build",
                    classLoader.loadClass("mtopsdk.mtop.domain.MethodEnum"),
                    String.class, String.class, String.class, String.class, String.class);

            // MethodEnum.GET
            Object methodEnum = classLoader.loadClass("mtopsdk.mtop.domain.MethodEnum")
                    .getField("GET").get(null);

            // 简化：使用 MtopBuilder
            // MtopBuilder mb = mtopInstance.build(req)
            Class<?> reqClass = classLoader.loadClass("mtopsdk.mtop.domain.MtopRequest");
            Object req = reqClass.getConstructor().newInstance();
            reqClass.getMethod("setApiName", String.class).invoke(req, apiName);
            reqClass.getMethod("setVersion", String.class).invoke(req, version);
            reqClass.getMethod("setNeedEcode", boolean.class).invoke(req, true);
            reqClass.getMethod("setNeedSession", boolean.class).invoke(req, true);
            reqClass.getMethod("setData", String.class).invoke(req, params);

            Method buildMethod = mtopInstance.getClass().getMethod("build", reqClass);
            Object builder = buildMethod.invoke(mtopInstance, req);

            // 注册 listener
            Class<?> listenerClass = classLoader.loadClass("mtopsdk.mtop.intf.Mtop$MtopCallback");
            // 实际用 MtopListener
            Class<?> mtopListenerClass = classLoader.loadClass("mtopsdk.mtop.intf.MtopListener");

            java.lang.reflect.Proxy proxy = (java.lang.reflect.Proxy) java.lang.reflect.Proxy.newProxyInstance(
                    classLoader, new Class<?>[]{mtopListenerClass},
                    (proxyObj, method, args) -> {
                        if ("onSuccess".equals(method.getName()) && args.length >= 1) {
                            try {
                                Object response = args[0];
                                Class<?> responseClass = classLoader.loadClass("mtopsdk.mtop.domain.MtopResponse");
                                Object bytedata = responseClass.getMethod("bytedata").invoke(response);
                                if (bytedata instanceof byte[]) {
                                    resultRef.set(new String((byte[]) bytedata, "UTF-8"));
                                }
                                latch.countDown();
                            } catch (Throwable e) {
                                errorRef.set(e);
                                latch.countDown();
                            }
                        } else if ("onError".equals(method.getName()) && args.length >= 1) {
                            Log.i(TAG, "MTOP error: " + apiName + " " + args[0]);
                            latch.countDown();
                        } else if ("onFinished".equals(method.getName())) {
                            latch.countDown();
                        }
                        return null;
                    });

            builder.getClass().getMethod("regListener", mtopListenerClass).invoke(builder, proxy);
            builder.getClass().getMethod("request").invoke(builder);

            boolean done = latch.await(30, TimeUnit.SECONDS);
            if (!done) {
                Log.i(TAG, "MTOP timeout: " + apiName);
                return null;
            }
            if (errorRef.get() != null) {
                Log.printStackTrace(TAG, errorRef.get());
                return null;
            }
            String result = resultRef.get();
            Log.debug("Taobao MTOP\nAPI: " + apiName + "\nParams: " + params + "\nData: " + result);
            return result;
        } catch (Throwable t) {
            Log.printStackTrace(TAG, t);
            return null;
        }
    }

    private static Object getMtopInstance(Class<?> mtopClass) throws Exception {
        // Mtop.instance(String instanceId, Context context) 或 Mtop.getInstance(Context)
        // 先试 getInstance(Context)
        try {
            Context ctx = context;
            if (ctx != null) {
                Method m = mtopClass.getMethod("getInstance", Context.class);
                return m.invoke(null, ctx);
            }
        } catch (Throwable ignored) {
        }
        // 试 Mtop.instance
        if (context != null) {
            try {
                Method m = mtopClass.getMethod("instance", String.class, Context.class);
                return m.invoke(null, "MTOP_ID_DTAOBAO", context);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    public static void setContext(Context ctx) {
        context = ctx;
        mainHandler = new Handler(Looper.getMainLooper());
    }
}
