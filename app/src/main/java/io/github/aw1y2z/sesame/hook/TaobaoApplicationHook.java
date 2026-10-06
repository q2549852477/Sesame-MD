package io.github.aw1y2z.sesame.hook;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;

import io.github.aw1y2z.sesame.util.Log;
import io.github.aw1y2z.sesame.util.XHelpers;
import io.github.aw1y2z.sesame.util.compat.XC_LoadPackage;
import io.github.aw1y2z.sesame.util.compat.XC_MethodHook;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import lombok.Getter;

/**
 * Hook 淘宝 App (com.taobao.taobao)，获取 MTOP SDK 调用能力 + Activity 滑动能力。
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
    private static volatile Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 当前前台 Activity（用于滑动操作） */
    @Getter
    @SuppressLint("StaticFieldLeak")
    private static volatile Activity currentActivity = null;

    private static Class<?> mtopClassRef = null;

    public static void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TAOBAO_PACKAGE.equals(lpparam.packageName)) return;
        if (hooked) return;
        classLoader = lpparam.classLoader;
        Log.i(TAG, "Loading Taobao hook, process=" + lpparam.processName);
        try {
            hookMtop(lpparam.classLoader);
            hookActivity(lpparam);
            hooked = true;
            Log.i(TAG, "Taobao hook loaded OK");
        } catch (Throwable t) {
            Log.printStackTrace(TAG, t);
        }
    }

    private static void hookMtop(ClassLoader loader) throws Exception {
        Class<?> mtopClass = loader.loadClass("mtopsdk.mtop.intf.Mtop");
        mtopClassRef = mtopClass;
        Log.i(TAG, "MTOP class loaded: " + mtopClass.getName());
    }

    /** Hook Activity.onResume 以捕获前台 Activity（用于滑动） */
    private static void hookActivity(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XHelpers.findAndHookMethod("android.app.Activity", lpparam.classLoader,
                    "onResume", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Activity act = (Activity) param.thisObject;
                            currentActivity = act;
                            if (context == null) {
                                context = act.getApplicationContext();
                            }
                        }
                    });
            Log.i(TAG, "Activity.onResume hooked");
        } catch (Throwable t) {
            Log.i(TAG, "hookActivity err: " + t.getMessage());
        }
    }

    /**
     * 在指定视图上模拟向上滑动（浏览效果）。
     * 从视图下半部分滑到上半部分，模拟用户向上滚动页面。
     */
    public static void swipeUp(View view) {
        if (view == null || !view.isShown()) return;
        int w = view.getWidth();
        int h = view.getHeight();
        if (w == 0 || h == 0) return;
        float startX = w / 2f + (float) (Math.random() * 40 - 20);
        float startY = h * 0.75f;
        float endX = w / 2f + (float) (Math.random() * 40 - 20);
        float endY = h * 0.25f;
        MotionEventSimulator.simulateSwipe(view, startX, startY, endX, endY, 600 + (int) (Math.random() * 400));
    }

    /** 获取当前 Activity 的 DecorView（作为滑动目标） */
    public static View getSwipeTarget() {
        Activity act = currentActivity;
        if (act == null) return null;
        Window window = act.getWindow();
        if (window == null) return null;
        return window.getDecorView();
    }

    /** 模拟点击视图中心 */
    public static void tap(View view) {
        if (view == null || !view.isShown()) return;
        int[] loc = new int[2];
        view.getLocationOnScreen(loc);
        float x = loc[0] + view.getWidth() / 2f;
        float y = loc[1] + view.getHeight() / 2f;
        MotionEventSimulator.simulateSwipe(view, x, y, x, y, 100);
    }

    // ==================== MTOP 请求 ====================

    public static String requestString(String apiName, String version, String params) {
        if (classLoader == null) {
            Log.i(TAG, "Taobao not hooked yet");
            return null;
        }
        try {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> resultRef = new AtomicReference<>();
            AtomicReference<Throwable> errorRef = new AtomicReference<>();

            Object mtopInstance = getMtopInstance();
            if (mtopInstance == null) {
                Log.i(TAG, "Cannot get Mtop instance");
                return null;
            }

            Class<?> reqClass = classLoader.loadClass("mtopsdk.mtop.domain.MtopRequest");
            Object req = reqClass.getConstructor().newInstance();
            reqClass.getMethod("setApiName", String.class).invoke(req, apiName);
            reqClass.getMethod("setVersion", String.class).invoke(req, version);
            reqClass.getMethod("setNeedEcode", boolean.class).invoke(req, true);
            reqClass.getMethod("setNeedSession", boolean.class).invoke(req, true);
            reqClass.getMethod("setData", String.class).invoke(req, params);

            Method buildMethod = mtopInstance.getClass().getMethod("build", reqClass);
            Object builder = buildMethod.invoke(mtopInstance, req);

            Class<?> mtopListenerClass = classLoader.loadClass("mtopsdk.mtop.common.MtopListener");

            Proxy proxy = (Proxy) Proxy.newProxyInstance(
                    classLoader, new Class<?>[]{mtopListenerClass},
                    (proxyObj, method, args) -> {
                        String mn = method.getName();
                        if ("onSuccess".equals(mn) && args.length >= 1) {
                            try {
                                Object response = args[0];
                                Method getData = response.getClass().getMethod("getData");
                                Object data = getData.invoke(response);
                                if (data instanceof byte[]) {
                                    resultRef.set(new String((byte[]) data, "UTF-8"));
                                } else if (data != null) {
                                    resultRef.set(data.toString());
                                }
                                latch.countDown();
                            } catch (Throwable e) {
                                // 尝试 bytedata
                                try {
                                    Object response2 = args[0];
                                    Method bytedata = response2.getClass().getMethod("bytedata");
                                    Object bd = bytedata.invoke(response2);
                                    if (bd instanceof byte[]) {
                                        resultRef.set(new String((byte[]) bd, "UTF-8"));
                                    }
                                } catch (Throwable e2) {
                                    errorRef.set(e2);
                                }
                                latch.countDown();
                            }
                        } else if ("onError".equals(mn)) {
                            Log.i(TAG, "MTOP onError: " + apiName);
                            latch.countDown();
                        } else if ("onFinished".equals(mn)) {
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
            Log.debug("Taobao MTOP " + apiName + " -> " + (result != null ? result.substring(0, Math.min(200, result.length())) : "null"));
            return result;
        } catch (Throwable t) {
            Log.printStackTrace(TAG, t);
            return null;
        }
    }

    private static Object getMtopInstance() throws Exception {
        if (mtopClassRef == null) return null;
        // Mtop.instance(String, Context)
        if (context != null) {
            try {
                Method m = mtopClassRef.getMethod("instance", String.class, Context.class);
                return m.invoke(null, "MTOP_ID_DTAOBAO", context);
            } catch (Throwable ignored) {
            }
            try {
                Method m = mtopClassRef.getMethod("getInstance", Context.class);
                return m.invoke(null, context);
            } catch (Throwable ignored) {
            }
            // 无参
            try {
                Method m = mtopClassRef.getMethod("instance");
                return m.invoke(null);
            } catch (Throwable ignored) {
            }
        }
        // 尝试无 Context
        try {
            Method m = mtopClassRef.getMethod("getInstance");
            return m.invoke(null);
        } catch (Throwable ignored) {
        }
        return null;
    }
}
