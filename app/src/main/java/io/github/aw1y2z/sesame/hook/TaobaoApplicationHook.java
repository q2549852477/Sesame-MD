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
            Object mtopInstance = getMtopInstance();
            if (mtopInstance == null) {
                android.util.Log.w(TAG, "requestString: no Mtop instance for " + apiName);
                return null;
            }
            if (sessionDebugOnce.compareAndSet(false, true)) {
                dumpSession(mtopInstance);
                dumpAllMtopInstances();
                try {
                    Object sid = mtopInstance.getClass().getMethod("getSid").invoke(mtopInstance);
                    Object uid = mtopInstance.getClass().getMethod("getUserId").invoke(mtopInstance);
                    android.util.Log.i(TAG, "CHOSEN mtop: sid=" + sid + " userId=" + uid);
                } catch (Throwable ignored) {}
            }

            Class<?> reqClass = classLoader.loadClass("mtopsdk.mtop.domain.MtopRequest");
            Object req = reqClass.getConstructor().newInstance();
            reqClass.getMethod("setApiName", String.class).invoke(req, apiName);
            reqClass.getMethod("setVersion", String.class).invoke(req, version);
            reqClass.getMethod("setNeedEcode", boolean.class).invoke(req, true);
            reqClass.getMethod("setNeedSession", boolean.class).invoke(req, true);
            reqClass.getMethod("setData", String.class).invoke(req, params);

            // build(MtopRequest, String apiVersion) -> MtopBuilder
            Method buildMethod = mtopInstance.getClass().getMethod("build", reqClass, String.class);
            Object builder = buildMethod.invoke(mtopInstance, req, version);
            android.util.Log.i(TAG, "MTOP debug: builder=" + (builder != null ? builder.getClass().getName() : "null") + " for " + apiName);

            // 用 syncRequest() 阻塞执行并直接返回 MtopResponse（异步 listener 回调在本环境不触发，
            // 表现为 ApiID.call=null、30s 超时）。syncRequest 内部走同一网络栈，返回带 retCode 的响应对象。
            Object response = builder.getClass().getMethod("syncRequest").invoke(builder);
            if (response == null) {
                android.util.Log.w(TAG, "MTOP syncRequest returned null: " + apiName);
                return null;
            }
            android.util.Log.i(TAG, "MTOP debug: syncRequest -> " + response.getClass().getName() + " for " + apiName);

            // 从 MtopResponse 取数据：优先 bytedata()（byte[]），退而 getData()
            String dataStr = extractResponseData(response);
            // 记录 retCode 便于诊断（FAIL_SYS_* 说明业务/签名问题）
            String retCode = null;
            try {
                retCode = String.valueOf(response.getClass().getMethod("getRetCode").invoke(response));
            } catch (Throwable ignored) {}
            android.util.Log.i(TAG, "MTOP " + apiName + " retCode=" + retCode
                    + " data=" + (dataStr != null ? dataStr.substring(0, Math.min(200, dataStr.length())) : "null"));
            return dataStr;
        } catch (Throwable t) {
            android.util.Log.e(TAG, "requestString exception: " + apiName, t);
            return null;
        }
    }

    private static final java.util.concurrent.atomic.AtomicBoolean sessionDebugOnce =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 诊断 Mtop 实例的登录/session 状态 */
    private static void dumpSession(Object mtopInstance) {
        StringBuilder sb = new StringBuilder();
        sb.append("mtop=").append(mtopInstance.getClass().getName()).append("\n");
        // 常见 session 查询方法
        for (String m : new String[]{"isSessionValid", "isLogined", "isLogin", "getSessionId", "getSid"}) {
            try {
                Object v = mtopInstance.getClass().getMethod(m).invoke(mtopInstance);
                sb.append(m).append("=").append(v).append("\n");
            } catch (Throwable ignored) {}
        }
        // 列出该实例的所有方法名（帮助找 session/登录相关 API）
        try {
            for (Method mm : mtopInstance.getClass().getMethods()) {
                String n = mm.getName().toLowerCase();
                if (n.contains("session") || n.contains("login") || n.contains("sid") || n.contains("cookie") || n.contains("user")) {
                    sb.append("M.").append(mm.getName()).append("(").append(paramStr(mm)).append(")\n");
                }
            }
        } catch (Throwable ignored) {}
        // 列出实例字段（含父类）当前值，定位 sid/session 存储位置
        try {
            Class<?> c = mtopInstance.getClass();
            while (c != null && c != Object.class) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    Object v = f.get(mtopInstance);
                    String vn = String.valueOf(v);
                    if (vn != null && (vn.length() < 80)) {
                        sb.append("F.").append(c.getSimpleName()).append(".").append(f.getName())
                          .append("=").append(vn).append("\n");
                    }
                }
                c = c.getSuperclass();
            }
        } catch (Throwable t) {
            sb.append("F.err=").append(t.getMessage()).append("\n");
        }
        // 深入 MtopConfig：sid/session 实际存这里
        try {
            java.lang.reflect.Field cfgField = findField(mtopInstance, "mtopConfig");
            if (cfgField != null) {
                Object cfg = cfgField.get(mtopInstance);
                sb.append("--- MtopConfig fields ---\n");
                Class<?> cc = cfg.getClass();
                while (cc != null && cc != Object.class) {
                    for (java.lang.reflect.Field f : cc.getDeclaredFields()) {
                        if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                        f.setAccessible(true);
                        Object v = f.get(cfg);
                        String vn = String.valueOf(v);
                        if (vn != null && (vn.length() < 120)) {
                            sb.append("CFG.").append(cc.getSimpleName()).append(".").append(f.getName())
                              .append("=").append(vn).append("\n");
                        }
                    }
                    cc = cc.getSuperclass();
                }
            }
        } catch (Throwable t) {
            sb.append("CFG.err=").append(t.getMessage()).append("\n");
        }
        android.util.Log.i(TAG, "SESSION DIAG:\n" + sb);
    }

    /** 遍历 Mtop 静态注册表里的所有实例，找出 appKey/sid 非空（真正登录）的那个 */
    private static void dumpAllMtopInstances() {
        try {
            // Mtop 静态字段里通常有个 Map<String, Mtop> 存所有实例
            for (java.lang.reflect.Field sf : mtopClassRef.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(sf.getModifiers())) continue;
                sf.setAccessible(true);
                Object val = sf.get(null);
                if (val instanceof java.util.Map) {
                    java.util.Map<?, ?> map = (java.util.Map<?, ?>) val;
                    for (java.util.Map.Entry<?, ?> e : map.entrySet()) {
                        Object inst = e.getValue();
                        if (inst == null) continue;
                        String appKey = null, sid = null, userId = null;
                        try {
                            java.lang.reflect.Field cf = findField(inst, "mtopConfig");
                            if (cf != null) {
                                Object cfg = cf.get(inst);
                                if (cfg != null) {
                                    java.lang.reflect.Field ak = findField(cfg, "appKey");
                                    if (ak != null) { ak.setAccessible(true); appKey = String.valueOf(ak.get(cfg)); }
                                }
                            }
                            sid = String.valueOf(inst.getClass().getMethod("getSid").invoke(inst));
                            try { userId = String.valueOf(inst.getClass().getMethod("getUserId").invoke(inst)); } catch (Throwable ignored) {}
                        } catch (Throwable ignored) {}
                        android.util.Log.i(TAG, "MTOP-INST key=" + e.getKey()
                                + " appKey=" + appKey + " sid=" + sid + " userId=" + userId);
                    }
                }
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "dumpAllMtopInstances: " + t.getMessage());
        }
    }

    private static java.lang.reflect.Field findField(Object obj, String name) {
        Class<?> c = obj.getClass();
        while (c != null && c != Object.class) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        return null;
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

    /** 从 MtopResponse 提取响应体字符串：优先 bytedata()，退而 getData() */
    private static String extractResponseData(Object response) {
        try {
            Object bd;
            try {
                bd = response.getClass().getMethod("bytedata").invoke(response);
            } catch (Throwable e) {
                bd = response.getClass().getMethod("getData").invoke(response);
            }
            if (bd instanceof byte[]) {
                return new String((byte[]) bd, "UTF-8");
            }
            return bd != null ? bd.toString() : null;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "extractResponseData: " + t.getMessage());
            return null;
        }
    }

    private static Object getMtopInstance() {
        if (mtopClassRef == null) return null;
        // 优先用「登录态」实例：遍历 Mtop 静态注册表，找 sid 非空的那个
        // （实测 MTOP_ID_TAOBAO 是空实例，真正带 sid 的是 INNER）
        Object logged = findLoggedInInstance();
        if (logged != null) return logged;
        // 兜底：按 instanceId 取
        if (context != null) {
            for (String id : new String[]{"INNER", "MTOP_ID_TAOBAO"}) {
                try {
                    Object o = mtopClassRef.getMethod("instance", String.class, Context.class)
                            .invoke(null, id, context);
                    if (o != null) return o;
                } catch (Throwable ignored) {}
            }
            try {
                return mtopClassRef.getMethod("instance", Context.class).invoke(null, context);
            } catch (Throwable ignored) {}
        }
        for (String id : new String[]{"INNER", "MTOP_ID_TAOBAO"}) {
            try {
                Object o = mtopClassRef.getMethod("getInstance", String.class).invoke(null, id);
                if (o != null) return o;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** 遍历 Mtop 静态 Map，返回 sid 非空的登录实例；找不到返回 null */
    private static Object findLoggedInInstance() {
        try {
            for (java.lang.reflect.Field sf : mtopClassRef.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(sf.getModifiers())) continue;
                sf.setAccessible(true);
                Object val = sf.get(null);
                if (!(val instanceof java.util.Map)) continue;
                for (Object eObj : ((java.util.Map<?, ?>) val).values()) {
                    if (eObj == null) continue;
                    try {
                        Object sid = eObj.getClass().getMethod("getSid").invoke(eObj);
                        if (sid != null && !String.valueOf(sid).isEmpty()
                                && !"null".equals(String.valueOf(sid))) {
                            return eObj;
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
