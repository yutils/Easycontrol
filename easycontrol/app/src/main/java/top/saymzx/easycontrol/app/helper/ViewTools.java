package top.saymzx.easycontrol.app.helper;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.Looper;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewPropertyAnimator;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ScrollView;

import java.util.Locale;

import top.saymzx.easycontrol.app.R;
import top.saymzx.easycontrol.app.databinding.ItemLoadingBinding;
import top.saymzx.easycontrol.app.databinding.ItemSpinnerBinding;
import top.saymzx.easycontrol.app.databinding.ItemSwitchBinding;
import top.saymzx.easycontrol.app.databinding.ItemTextBinding;
import top.saymzx.easycontrol.app.databinding.ModuleDialogBinding;
import top.saymzx.easycontrol.app.entity.AppData;
import top.saymzx.easycontrol.app.entity.MyInterface;

public class ViewTools {
    // 设置全面屏
    public static void setFullScreen(Activity context) {
        // 全屏显示
        context.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        context.getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    // 设置语言
    public static void setLocale(Activity context) {
        Resources resources = context.getResources();
        Configuration config = resources.getConfiguration();
        String locale = AppData.setting.getLocale();
        if (locale.equals("")) config.locale = Locale.getDefault();
        else if (locale.equals("en")) config.locale = Locale.ENGLISH;
        else if (locale.equals("zh")) config.locale = Locale.CHINESE;
        resources.updateConfiguration(config, resources.getDisplayMetrics());
    }

    // 设置状态栏导航栏颜色
    public static void setStatusAndNavBar(Activity context) {
        // 导航栏
        context.getWindow().setNavigationBarColor(context.getResources().getColor(R.color.background));
        // 状态栏
        context.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        context.getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        context.getWindow().setStatusBarColor(context.getResources().getColor(R.color.background));
        if ((context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES)
            context.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        // 设置异形屏
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = context.getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            context.getWindow().setAttributes(lp);
        }
    }

    // 创建弹窗
    public static Dialog createDialog(Context context, boolean canCancel, View view) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setCancelable(canCancel);
        ScrollView dialogView = ModuleDialogBinding.inflate(LayoutInflater.from(context)).getRoot();
        dialogView.addView(view);
        builder.setView(dialogView);
        Dialog dialog = builder.create();
        dialog.setCanceledOnTouchOutside(canCancel);
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        return dialog;
    }

    // 创建Client加载框
    // 使用普通 Dialog 直接承载内容卡片：不走 AlertDialog(其面板会被主题拉伸成近全屏宽度)，
    // WRAP_CONTENT 让弹窗紧贴 120dp 的加载卡片，不再有近全屏的白色面板。
    public static Pair<ItemLoadingBinding, Dialog> createLoading(Context context) {
        // 必须带 parent 展开(root 不为 null 才会从 XML 生成 LayoutParams)，再把布局参数传给 setContentView：
        // setContentView(View) 会把参数替换成 MATCH_PARENT，120dp 的加载卡片会被收缩成"仅贴内容"的小卡片。
        ItemLoadingBinding loadingView = ItemLoadingBinding.inflate(LayoutInflater.from(context), new FrameLayout(context), false);
        Dialog dialog = new Dialog(context);
        dialog.setContentView(loadingView.getRoot(), loadingView.getRoot().getLayoutParams());
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            // 普通 Dialog 不套用 AlertDialog 的最小宽度主题，内容卡片宽多少弹窗就多宽
            WindowManager.LayoutParams layoutParams = window.getAttributes();
            layoutParams.width = WindowManager.LayoutParams.WRAP_CONTENT;
            layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
            window.setAttributes(layoutParams);
        }
        return new Pair<>(loadingView, dialog);
    }

    // 安全关闭弹窗：宿主 Activity 可能已被销毁(旋转/退出)，此时 Dialog 的 DecorView 已从 WindowManager 移除，
    // 直接 dismiss/cancel 会抛 "View not attached to window manager"。
    // 必须在 UI 线程同步执行并捕获该异常：后台线程调用 Dialog.dismiss 只是把 mDismissAction post 到 UI 线程，异常不会回到调用方。
    public static void dismiss(Dialog dialog) {
        if (dialog == null || AppData.uiHandler == null) return;
        if (Looper.myLooper() != AppData.uiHandler.getLooper()) {
            AppData.uiHandler.post(() -> dismiss(dialog));
            return;
        }
        try {
            if (dialog.isShowing()) dialog.cancel();
        } catch (IllegalArgumentException ignored) {
            // 宿主 Activity 已销毁，忽略
        }
    }

    // 创建纯文本卡片
    public static ItemTextBinding createTextCard(
            Context context,
            String text,
            MyInterface.MyFunction function
    ) {
        ItemTextBinding textView = ItemTextBinding.inflate(LayoutInflater.from(context));
        textView.text.setText(text);
        if (function != null) textView.getRoot().setOnClickListener(v -> function.run());
        return textView;
    }

    // 创建开关卡片
    public static ItemSwitchBinding createSwitchCard(
            Context context,
            String text,
            String textDetail,
            boolean config,
            MyInterface.MyFunctionBoolean function
    ) {
        ItemSwitchBinding switchView = ItemSwitchBinding.inflate(LayoutInflater.from(context));
        switchView.itemText.setText(text);
        switchView.itemDetail.setText(textDetail);
        switchView.itemSwitch.setChecked(config);
        if (function != null)
            switchView.itemSwitch.setOnCheckedChangeListener((buttonView, checked) -> function.run(checked));
        return switchView;
    }

    // 创建列表卡片
    public static ItemSpinnerBinding createSpinnerCard(
            Context context,
            String text,
            String textDetail,
            String config,
            ArrayAdapter<String> adapter,
            MyInterface.MyFunctionString function
    ) {
        ItemSpinnerBinding spinnerView = ItemSpinnerBinding.inflate(LayoutInflater.from(context));
        spinnerView.itemText.setText(text);
        spinnerView.itemDetail.setText(textDetail);
        spinnerView.itemSpinner.setAdapter(adapter);
        spinnerView.itemSpinner.setSelection(adapter.getPosition(config));
        spinnerView.itemSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (function != null)
                    function.run(spinnerView.itemSpinner.getSelectedItem().toString());
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        return spinnerView;
    }

    // 更改View的形态
    public static void viewAnim(View view, boolean toShowView, int translationX, int translationY, MyInterface.MyFunctionBoolean action) {
        // 创建平移动画
        view.setTranslationX(toShowView ? translationX : 0);
        float endX = toShowView ? 0 : translationX;
        view.setTranslationY(toShowView ? translationY : 0);
        float endY = toShowView ? 0 : translationY;
        // 创建透明度动画
        view.setAlpha(toShowView ? 0f : 1f);
        float endAlpha = toShowView ? 1f : 0f;

        // 设置动画时长和插值器
        ViewPropertyAnimator animator = view.animate()
                .translationX(endX)
                .translationY(endY)
                .alpha(endAlpha)
                .setDuration(toShowView ? 300 : 200)
                .setInterpolator(toShowView ? new OvershootInterpolator() : new DecelerateInterpolator());
        animator.withStartAction(() -> {
            if (action != null) action.run(true);
        });
        animator.withEndAction(() -> {
            if (action != null) action.run(false);
        });

        // 启动动画
        animator.start();
    }
}
