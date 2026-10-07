package io.github.aw1y2z.sesame.hook;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;

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
 * MTOP 类加载延迟到 Activity.onResume 时执行（此时 split APK 已加载完）。
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

    @Getter
    @SuppressLint("StaticFieldLeak")
    private static volatile Activity currentActivity = null;

    private static volatile Class<?> mtopClassRef = null;
    private static volatile boolean mtopReady = false;

    private static volatile BroadcastReceiver broadcastReceiver = null;
    private static volatile boolean receiverRegistered = false;
    private static volatile boolean taskRunning = false;

    public static void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TAOBAO_PACKAGE.equals(lpparam.packageName)) return;
        if (hooked) return;
        hooked = true;
        classLoader = lpparam.classLoader;
        android.util.Log.i(TAG, "Taobao hook initialized, process=" + lpparam.processName);
        try {
            hookActivity(lpparam);
        } catch (Throwable t) {
            android.util.Log.e(TAG, "hookActivity failed: " + t.getMessage(), t);
        }
    }

    /** 注册广播接收器，接收来自模块 UI 的执行指令 */
    private static void registerBroadcast(Context ctx) {
        if (receiverRegistered) return;
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction("com.taobao.taobao.sesame.execute");
            filter.addAction("com.taobao.taobao.sesame.restart");
            filter.addAction("com.taobao.taobao.sesame.status");

            broadcastReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (action == null) return;
                    android.util.Log.i(TAG, "Broadcast received: " + action);
                    switch (action) {
                        case "com.taobao.taobao.sesame.status":
                            // 回复模块 UI：hook 已激活
                            context.sendBroadcast(new Intent("io.github.aw1y2z.sesame.taobao.status"));
                            break;
                        case "com.taobao.taobao.sesame.execute":
                            BroadcastReceiver.PendingResult pr = goAsync();
                            String taskType = intent.getStringExtra("task");
                            new Thread(() -> {
                                try {
                                    executeTask(taskType);
                                } catch (Throwable t) {
                                    android.util.Log.e(TAG, "executeTask failed: " + t.getMessage(), t);
                                }
                                pr.finish();
                            }, "taobao-execute").start();
                            break;
                        case "com.taobao.taobao.sesame.restart":
                            BroadcastReceiver.PendingResult pr2 = goAsync();
                            new Thread(() -> {
                                try {
                                    // 重新读取配置并执行全部任务
                                    executeTask("all");
                                } catch (Throwable t) {
                                    android.util.Log.e(TAG, "restart failed: " + t.getMessage(), t);
                                }
                                pr2.finish();
                            }, "taobao-restart").start();
                            break;
                    }
                }
            };
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(broadcastReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(broadcastReceiver, filter);
            }
            receiverRegistered = true;
            android.util.Log.i(TAG, "Broadcast receiver registered");
        } catch (Throwable t) {
            android.util.Log.e(TAG, "registerBroadcast failed: " + t.getMessage(), t);
        }
    }

    /**
     * 淘宝进程无法读取支付宝私有配置目录（不同 uid，EACCES），
     * 所以 enable/参数改从淘宝自己私有目录下的文件读取（root 负责写入）。
     * 文件格式（逐行）：第1行 enable(0/1)，第2行 每日浇水次数，第3行 每次浏览滑动次数。
     */
    private static final String TB_CONFIG_FILE = "/data/data/com.taobao.taobao/sesame_tb.txt";

    /** 读淘宝私有配置文件，返回 [enable, waterCount, browseSwipes]，失败返回 null */
    private static int[] readTaobaoConfig() {
        try {
            java.io.File f = new java.io.File(TB_CONFIG_FILE);
            if (!f.exists()) return null;
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
                String l1 = r.readLine();
                String l2 = r.readLine();
                String l3 = r.readLine();
                int enable = (l1 != null && "1".equals(l1.trim())) ? 1 : 0;
                int water = l2 != null ? Integer.parseInt(l2.trim()) : 10;
                int swipes = l3 != null ? Integer.parseInt(l3.trim()) : 3;
                return new int[]{enable, water, swipes};
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "readTaobaoConfig failed: " + t.getMessage());
            return null;
        }
    }

    /** 淘宝 onResume 时自动检查：如果淘宝农场 enabled 就执行全部任务 */
    private static void autoExecuteIfEnabled() {
        ClassLoader moduleLoader = getModuleClassLoader();
        try {
            int[] cfg = readTaobaoConfig();
            if (cfg == null) {
                android.util.Log.d(TAG, "TaobaoFarm: no config file (" + TB_CONFIG_FILE + "), skip");
                return;
            }
            if (cfg[0] != 1) {
                android.util.Log.d(TAG, "TaobaoFarm not enabled, skip");
                return;
            }
            // 初始化 model（字段用默认值；enable 由文件决定，不走支付宝配置）
            Class<?> modelClass = moduleLoader.loadClass("io.github.aw1y2z.sesame.data.Model");
            modelClass.getMethod("initAllModel").invoke(null);
            Class<?> farmClass = moduleLoader.loadClass(
                    "io.github.aw1y2z.sesame.model.task.taobaoFarm.TaobaoFarm");
            Object farmInstance = modelClass.getMethod("getModel", Class.class)
                    .invoke(null, farmClass);
            if (farmInstance == null) return;
            // 应用文件里的浇水/滑动次数（IntegerModelField 通过 setConfigValue 写）
            applyTaobaoParams(farmClass, farmInstance, cfg[1], cfg[2]);
            android.util.Log.i(TAG, "Auto-execute TaobaoFarm (water=" + cfg[1] + ", swipes=" + cfg[2] + ")");
            java.lang.reflect.Method doAll = farmClass.getDeclaredMethod("doAllTasks");
            doAll.setAccessible(true);
            doAll.invoke(farmInstance);
        } catch (Throwable t) {
            android.util.Log.e(TAG, "autoExecuteIfEnabled: " + t.getMessage(), t);
        }
    }

    /** 把文件里的浇水/滑动次数写入 model 字段（反射，找不到字段则忽略用默认值） */
    private static void applyTaobaoParams(Class<?> farmClass, Object farmInstance, int water, int swipes) {
        try {
            Class<?> modelConfigClass = farmClass.getClassLoader().loadClass("io.github.aw1y2z.sesame.data.ModelConfig");
            Class<?> modelClass = farmClass.getClassLoader().loadClass("io.github.aw1y2z.sesame.data.Model");
            java.lang.reflect.Method getMap = modelClass.getMethod("getModelConfigMap");
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> map = (java.util.Map<String, Object>) getMap.invoke(null);
            Object cfg = map.get("TaobaoFarm");
            if (cfg == null) return;
            java.lang.reflect.Method getField = modelConfigClass.getMethod("getModelFieldExt", String.class);
            Object wf = getField.invoke(cfg, "tbFarmWaterCount");
            if (wf != null) wf.getClass().getMethod("setConfigValue", String.class).invoke(wf, String.valueOf(water));
            Object sf = getField.invoke(cfg, "tbFarmBrowseSwipes");
            if (sf != null) sf.getClass().getMethod("setConfigValue", String.class).invoke(sf, String.valueOf(swipes));
        } catch (Throwable t) {
            android.util.Log.w(TAG, "applyTaobaoParams: use defaults (" + t.getMessage() + ")");
        }
    }

    /** 模块自身的 classloader（不是宿主的） */
    private static ClassLoader getModuleClassLoader() {
        return TaobaoApplicationHook.class.getClassLoader();
    }

    /** 在指定线程上执行任务（由广播触发） */
    private static void executeTask(String taskType) {
        android.util.Log.i(TAG, "executeTask: " + taskType);
        ClassLoader moduleLoader = getModuleClassLoader();
        try {
            // 加载配置（字段值从磁盘读取）— 用模块 classloader
            Class<?> configV2Class = moduleLoader.loadClass("io.github.aw1y2z.sesame.data.ConfigV2");
            configV2Class.getMethod("load", String.class).invoke(null, (Object) "");

            // 初始化 model 注册表
            Class<?> modelClass = moduleLoader.loadClass("io.github.aw1y2z.sesame.data.Model");
            modelClass.getMethod("initAllModel").invoke(null);

            // 获取已初始化的 TaobaoFarm 实例（字段值已从配置加载）
            Class<?> farmClass = moduleLoader.loadClass(
                    "io.github.aw1y2z.sesame.model.task.taobaoFarm.TaobaoFarm");
            Object farmInstance = modelClass.getMethod("getModel", Class.class)
                    .invoke(null, farmClass);
            if (farmInstance == null) {
                farmInstance = farmClass.getDeclaredConstructor().newInstance();
            }

            if ("status".equals(taskType)) {
                // 回复状态：写一个状态文件供 UI 读取
                try {
                    java.io.File statusFile = new java.io.File(TASK_STATUS_FILE);
                    statusFile.getParentFile().mkdirs();
                    StringBuilder sb = new StringBuilder();
                    sb.append("hooked=").append(hooked).append("\n");
                    sb.append("mtopReady=").append(mtopReady).append("\n");
                    sb.append("context=").append(context != null).append("\n");
                    sb.append("activity=").append(currentActivity != null).append("\n");
                    try (java.io.FileWriter fw = new java.io.FileWriter(statusFile)) {
                        fw.write(sb.toString());
                    }
                } catch (Throwable ignored) {}
                return;
            }

            String method;
            if ("water".equals(taskType)) {
                method = "doWater";
            } else {
                method = "doAllTasks";
            }
            java.lang.reflect.Method m = farmClass.getDeclaredMethod(method);
            m.setAccessible(true);
            m.invoke(farmInstance);
        } catch (Throwable t) {
            android.util.Log.e(TAG, "executeTask exception: " + t.getMessage(), t);
        }
    }

    /** 手动任务信号文件：root/ADB 写入淘宝私有目录，淘宝进程 onResume 时检查并一次性消费 */
    private static final String TASK_SIGNAL_FILE = "/data/data/com.taobao.taobao/sesame_tb_task";
    private static final String TASK_STATUS_FILE = "/data/data/com.taobao.taobao/sesame_tb_status";

    /** Hook Activity.onResume：捕获前台 Activity + 延迟初始化 MTOP + 检查任务信号 */
    private static void hookActivity(XC_LoadPackage.LoadPackageParam lpparam) {
        XHelpers.findAndHookMethod("android.app.Activity", lpparam.classLoader,
                "onResume", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Activity act = (Activity) param.thisObject;
                        currentActivity = act;
                        if (context == null) {
                            context = act.getApplicationContext();
                            registerBroadcast(context);
                        }
                        // 延迟初始化 MTOP
                        if (!mtopReady && !mtopInitFailed) {
                            try {
                                mtopClassRef = classLoader.loadClass("mtopsdk.mtop.intf.Mtop");
                                mtopReady = true;
                                android.util.Log.i(TAG, "MTOP class loaded: " + mtopClassRef.getName());
                            } catch (Throwable t) {
                                android.util.Log.w(TAG, "MTOP not ready yet: " + t.getMessage());
                            }
                        }
                        // 检查手动任务信号文件（淘宝私有目录，root/ADB 写入；一次性消费）
                        checkTaskSignal();
                        if (mtopReady && !taskRunning) {
                            taskRunning = true;
                            new Thread(() -> {
                                try {
                                    autoExecuteIfEnabled();
                                } catch (Throwable t) {
                                    android.util.Log.e(TAG, "autoExecute failed: " + t.getMessage(), t);
                                } finally {
                                    taskRunning = false;
                                }
                            }, "taobao-auto-task").start();
                        }
                    }
                });
        android.util.Log.i(TAG, "Activity.onResume hook installed");
    }

    /** 检查并消费任务信号文件 */
    private static void checkTaskSignal() {
        try {
            java.io.File signalFile = new java.io.File(TASK_SIGNAL_FILE);
            if (!signalFile.exists()) return;
            String taskType;
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.FileReader(signalFile))) {
                taskType = reader.readLine();
            }
            // 立即删除信号文件（一次性消费）
            signalFile.delete();
            if (taskType == null || taskType.trim().isEmpty()) return;
            final String task = taskType.trim();
            android.util.Log.i(TAG, "Task signal detected: " + task);
            new Thread(() -> {
                try {
                    executeTask(task);
                } catch (Throwable t) {
                    android.util.Log.e(TAG, "Task signal execute failed: " + t.getMessage(), t);
                }
            }, "taobao-signal-task").start();
        } catch (Throwable t) {
            android.util.Log.e(TAG, "checkTaskSignal error: " + t.getMessage(), t);
        }
    }

    private static volatile boolean mtopInitFailed = false;

    /** MTOP 是否已准备好 */
    public static boolean isMtopReady() {
        return mtopReady;
    }

    // ==================== 滑动/点击 ====================

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

    public static View getSwipeTarget() {
        Activity act = currentActivity;
        if (act == null) return null;
        Window window = act.getWindow();
        if (window == null) return null;
        return window.getDecorView();
    }

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
            android.util.Log.w(TAG, "requestString: not hooked");
            return null;
        }
        if (!mtopReady) {
            // 再试一次加载
            try {
                mtopClassRef = classLoader.loadClass("mtopsdk.mtop.intf.Mtop");
                mtopReady = true;
            } catch (Throwable t) {
                android.util.Log.e(TAG, "requestString: MTOP unavailable: " + t.getMessage());
                return null;
            }
        }
        try {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> resultRef = new AtomicReference<>();
            AtomicReference<Throwable> errorRef = new AtomicReference<>();

            Object mtopInstance = getMtopInstance();
            if (mtopInstance == null) {
                android.util.Log.w(TAG, "requestString: no Mtop instance for " + apiName);
                return null;
            }

            Class<?> reqClass = classLoader.loadClass("mtopsdk.mtop.domain.MtopRequest");
            Object req = reqClass.getConstructor().newInstance();
            reqClass.getMethod("setApiName", String.class).invoke(req, apiName);
            reqClass.getMethod("setVersion", String.class).invoke(req, version);
            reqClass.getMethod("setNeedEcode", boolean.class).invoke(req, true);
            reqClass.getMethod("setNeedSession", boolean.class).invoke(req, true);
            reqClass.getMethod("setData", String.class).invoke(req, params);

            // build(MtopRequest, String apiVersion) -> MtopBuilder
            Class<?> mtopBuilderClass = classLoader.loadClass("mtopsdk.mtop.intf.MtopBuilder");
            Method buildMethod = mtopInstance.getClass().getMethod("build", reqClass, String.class);
            Object builder = buildMethod.invoke(mtopInstance, req, version);
            android.util.Log.i(TAG, "MTOP debug: mtopInstance=" + mtopInstance.getClass().getName()
                    + " builder=" + (builder != null ? builder.getClass().getName() : "null") + " for " + apiName);
            dumpMethodsOnce(builder);

            Class<?> mtopListenerClass = classLoader.loadClass("mtopsdk.mtop.common.MtopListener");

            Proxy proxy = (Proxy) Proxy.newProxyInstance(
                    classLoader, new Class<?>[]{mtopListenerClass},
                    (proxyObj, method, args) -> {
                        String mn = method.getName();
                        android.util.Log.i(TAG, "MTOP listener cb: " + mn + " (" + apiName + ")");
                        if ("onSuccess".equals(mn) && args != null && args.length >= 1) {
                            try {
                                Object response = args[0];
                                try {
                                    Method getData = response.getClass().getMethod("getData");
                                    Object data = getData.invoke(response);
                                    if (data instanceof byte[]) {
                                        resultRef.set(new String((byte[]) data, "UTF-8"));
                                    } else if (data != null) {
                                        resultRef.set(data.toString());
                                    }
                                } catch (Throwable e) {
                                    Method bytedata = response.getClass().getMethod("bytedata");
                                    Object bd = bytedata.invoke(response);
                                    if (bd instanceof byte[]) {
                                        resultRef.set(new String((byte[]) bd, "UTF-8"));
                                    }
                                }
                                latch.countDown();
                            } catch (Throwable e) {
                                errorRef.set(e);
                                latch.countDown();
                            }
                        } else if ("onError".equals(mn)) {
                            android.util.Log.w(TAG, "MTOP onError: " + apiName);
                            latch.countDown();
                        } else if ("onFinished".equals(mn)) {
                            latch.countDown();
                        }
                        return null;
                    });

            // addListener(MtopListener) -> MtopBuilder
            builder.getClass().getMethod("addListener", mtopListenerClass).invoke(builder, proxy);
            // asyncRequest() -> ApiID
            Object apiId;
            try {
                apiId = builder.getClass().getMethod("asyncRequest").invoke(builder);
            } catch (Throwable ae) {
                // 某些 MTOP 版本方法名/签名不同，逐个尝试
                android.util.Log.w(TAG, "MTOP asyncRequest failed, trying alternatives: " + ae.getMessage());
                apiId = tryRequestMethods(builder);
            }
            android.util.Log.i(TAG, "MTOP debug: apiId=" + apiId + " for " + apiName);

            boolean done = latch.await(30, TimeUnit.SECONDS);
            if (!done) {
                android.util.Log.w(TAG, "MTOP timeout: " + apiName);
                return null;
            }
            if (errorRef.get() != null) {
                android.util.Log.e(TAG, "MTOP error: " + apiName, errorRef.get());
                return null;
            }
            String result = resultRef.get();
            android.util.Log.d(TAG, "MTOP " + apiName + " => " + (result != null ? result.substring(0, Math.min(150, result.length())) : "null"));
            return result;
        } catch (Throwable t) {
            android.util.Log.e(TAG, "requestString exception: " + apiName, t);
            return null;
        }
    }

    private static volatile boolean dumpedBuilderMethods = false;
    /** 打印 MtopBuilder 的方法名，用于确定正确的请求触发方法 */
    private static void dumpMethodsOnce(Object builder) {
        if (dumpedBuilderMethods || builder == null) return;
        dumpedBuilderMethods = true;
        try {
            StringBuilder sb = new StringBuilder();
            Class<?> c = builder.getClass();
            while (c != null && c != Object.class) {
                for (Method m : c.getMethods()) {
                    sb.append(c.getSimpleName()).append(".").append(m.getName())
                      .append("(").append(paramStr(m)).append(")\n");
                }
                c = c.getSuperclass();
            }
            android.util.Log.i(TAG, "MTOP methods:\n" + sb.toString());
        } catch (Throwable t) {
            android.util.Log.e(TAG, "dumpMethodsOnce: " + t.getMessage());
        }
    }

    private static String paramStr(Method m) {
        StringBuilder sb = new StringBuilder();
        Class<?>[] ps = m.getParameterTypes();
        for (int i = 0; i < ps.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(ps[i].getSimpleName());
        }
        return sb.toString();
    }

    /** asyncRequest 失败时尝试其它请求触发方法 */
    private static Object tryRequestMethods(Object builder) {
        String[] candidates = {"startRequest", "syncRequest", "request", "asyncSend", "sendRequest"};
        for (String cn : candidates) {
            try {
                Method m = builder.getClass().getMethod(cn);
                Object r = m.invoke(builder);
                android.util.Log.i(TAG, "MTOP debug: used request method " + cn);
                return r;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Object getMtopInstance() {
        if (mtopClassRef == null) return null;
        if (context != null) {
            // Mtop.instance(String instanceId, Context) — 淘宝主实例
            try {
                return mtopClassRef.getMethod("instance", String.class, Context.class)
                        .invoke(null, "MTOP_ID_TAOBAO", context);
            } catch (Throwable ignored) {
            }
            // Mtop.instance(Context) — 默认实例
            try {
                return mtopClassRef.getMethod("instance", Context.class).invoke(null, context);
            } catch (Throwable ignored) {
            }
            // Mtop.getInstance(String)
            try {
                return mtopClassRef.getMethod("getInstance", String.class).invoke(null, "MTOP_ID_TAOBAO");
            } catch (Throwable ignored) {
            }
        }
        try {
            return mtopClassRef.getMethod("getInstance", String.class).invoke(null, "MTOP_ID_TAOBAO");
        } catch (Throwable ignored) {
        }
        return null;
    }
}
