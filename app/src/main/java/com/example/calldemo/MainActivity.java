package com.example.calldemo;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import io.agora.media.RtcTokenBuilder2;
import io.agora.rtc2.Constants;
import io.agora.rtc2.IRtcEngineEventHandler;
import io.agora.rtc2.RtcEngine;
import io.agora.rtc2.video.VideoCanvas;
import io.agora.rtc2.video.VideoEncoderConfiguration;

public class MainActivity extends AppCompatActivity implements View.OnClickListener, SurfaceHolder.Callback {

    // 声网核心配置
    private static final String AGORA_APP_ID = "585ceb26ea044e649a7a39304d323dc7";
    private static final String CHANNEL_NAME = "HoneyFamily";
    private static final int LOCAL_UID = 1001;
    private static final String SERVER_URL = "http://of1wd11788567.vicp.fun/heima/token/getToken";

    // 权限请求码
    private static final int PERMISSION_REQUEST_CODE = 101;
    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.VIBRATE,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.INTERNET
    };

    private String currentToken = ""; // 保存当前有效Token

    private Vibrator vibrator;
    private RtcEngine mRtcEngine;
    private SurfaceView svLocal, svRemote;
    private Button btnAnswer, btnReject, btnHangup; // 新增接听、拒绝按钮
    private int mRemoteUid = -1;
    private PowerManager.WakeLock wakeLock;
    private Intent foregroundServiceIntent;
    private boolean isLocalPreviewStarted = false;
    private boolean isInCall = false; // 标记：是否已建立通话
    private boolean isRinging = false; // 标记：是否正在振铃

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 1. 检查并申请权限
        if (!checkPermissions()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE);
            return;
        }

        // 2. 初始化震动器
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);

        // 3. 初始化唤醒锁
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "CallDemo::VibrateWakeLock"
        );

        // 4. 启动前台服务
        foregroundServiceIntent = new Intent(this, CallForegroundService.class);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(foregroundServiceIntent);
        } else {
            startService(foregroundServiceIntent);
        }

        initView();
        try {
            initAgoraEngine();
            setupVideoConfig();
            svLocal.getHolder().addCallback(this);
            // 先获取Token，再加入频道（仅监听，不初始化音视频）
            getTokenFromServer(() -> joinChannelForListening());
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "初始化失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    // 检查权限是否全部授予
    private boolean checkPermissions() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    // 权限申请结果回调
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (!allGranted) {
                Toast.makeText(this, "必须授予所有权限才能使用通话功能", Toast.LENGTH_LONG).show();
                finish();
            } else {
                recreate();
            }
        }
    }

    private void initView() {
        svLocal = findViewById(R.id.sv_local);
        svRemote = findViewById(R.id.sv_remote);
        btnAnswer = findViewById(R.id.btn_answer); // 新增接听按钮
        btnReject = findViewById(R.id.btn_reject); // 新增拒绝按钮
        btnHangup = findViewById(R.id.btn_hangup);

        // 初始化按钮点击事件
        btnAnswer.setOnClickListener(this);
        btnReject.setOnClickListener(this);
        btnHangup.setOnClickListener(this);

        // 初始状态：只显示接听/拒绝按钮，隐藏挂断按钮，隐藏视频画面
        btnAnswer.setVisibility(View.VISIBLE);
        btnReject.setVisibility(View.VISIBLE);
        btnHangup.setVisibility(View.GONE);
        svLocal.setVisibility(View.GONE);
        svRemote.setVisibility(View.GONE);

        svLocal.setZOrderOnTop(true);
    }

    private void initAgoraEngine() throws Exception {
        mRtcEngine = RtcEngine.create(getApplicationContext(), AGORA_APP_ID, mRtcEventHandler);
        // 初始化时先关闭音视频（接听后再开启）
        mRtcEngine.disableVideo();
        mRtcEngine.disableAudio();
        mRtcEngine.setChannelProfile(Constants.CHANNEL_PROFILE_COMMUNICATION);
    }

    // 优化视频编码配置
    private void setupVideoConfig() {
        VideoEncoderConfiguration config = new VideoEncoderConfiguration(
                VideoEncoderConfiguration.VD_640x360,
                VideoEncoderConfiguration.FRAME_RATE.FRAME_RATE_FPS_15,
                VideoEncoderConfiguration.STANDARD_BITRATE,
                VideoEncoderConfiguration.ORIENTATION_MODE.ORIENTATION_MODE_ADAPTIVE
        );
        mRtcEngine.setVideoEncoderConfiguration(config);
    }

    // 加入频道仅用于监听来电（不开启音视频）
    private void joinChannelForListening() {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (currentToken.isEmpty()) {
                Toast.makeText(this, "Token为空，无法加入频道", Toast.LENGTH_SHORT).show();
                return;
            }
            // 加入频道，但不开启音视频
            mRtcEngine.joinChannel(currentToken, CHANNEL_NAME, null, LOCAL_UID);
            Toast.makeText(this, "已进入通话频道，等待来电...", Toast.LENGTH_SHORT).show();
        }, 1000);
    }

    // 接听通话：开启音视频、初始化预览、绑定远程视频
    private void answerCall() {
        if (isInCall || mRemoteUid == -1) {
            return;
        }

        try {
            // 1. 停止振铃
            stopRinging();

            // 2. 开启音视频
            mRtcEngine.enableVideo();
            mRtcEngine.enableAudio();
            mRtcEngine.setEnableSpeakerphone(true);
            mRtcEngine.muteLocalVideoStream(false);

            // 3. 显示视频画面，切换按钮状态
            svLocal.setVisibility(View.VISIBLE);
            svRemote.setVisibility(View.VISIBLE);
            btnAnswer.setVisibility(View.GONE);
            btnReject.setVisibility(View.GONE);
            btnHangup.setVisibility(View.VISIBLE);

            // 4. 初始化本地预览（如果Surface已创建）
            if (svLocal.getHolder().getSurface().isValid()) {
                setupLocalVideo();
            }

            // 5. 绑定远程视频
            setupRemoteVideo(mRemoteUid);

            // 6. 标记通话已建立
            isInCall = true;
            Toast.makeText(this, "已接听通话", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "接听失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // 拒绝通话：挂断并清理资源
    private void rejectCall() {
        stopRinging();
        hangupCall();
        Toast.makeText(this, "已拒绝通话", Toast.LENGTH_SHORT).show();
    }

    // 启动振铃
    private void startRinging() {
        if (isRinging) {
            return;
        }
        isRinging = true;

        // 唤醒屏幕
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(10*1000); // 持锁10秒
        }

        // 循环振铃（3秒一次，直到接听/拒绝）
        if (vibrator != null && vibrator.hasVibrator()) {
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    // 循环振铃：振动1秒，间隔2秒，重复无限次
                    VibrationEffect effect = VibrationEffect.createWaveform(
                            new long[]{0, 1000, 2000},
                            0 // 循环索引，0表示无限循环
                    );
                    vibrator.vibrate(effect);
                } else {
                    vibrator.vibrate(new long[]{0, 1000, 2000}, 0);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        runOnUiThread(() -> Toast.makeText(this, "有来电（UID：" + mRemoteUid + "），请接听/拒绝", Toast.LENGTH_LONG).show());
    }

    // 停止振铃
    private void stopRinging() {
        if (!isRinging) {
            return;
        }
        isRinging = false;

        if (vibrator != null) {
            vibrator.cancel();
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    // 从服务端获取Token（本地生成方式）
    private void getTokenFromServer(Runnable onSuccess) {
        new Thread(() -> {
            try {
                RtcTokenBuilder2 token = new RtcTokenBuilder2();
                currentToken = token.buildTokenWithUid(
                        AGORA_APP_ID,
                        "b90fc32735af48019f1a4392c12aee16",
                        CHANNEL_NAME,
                        LOCAL_UID,
                        RtcTokenBuilder2.Role.ROLE_PUBLISHER,
                        36000,
                        36000
                );

                runOnUiThread(onSuccess);
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Token获取成功", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Token获取异常：" + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    // Token续期方法
    private void renewToken() {
        getTokenFromServer(() -> {
            if (mRtcEngine != null && !currentToken.isEmpty()) {
                mRtcEngine.renewToken(currentToken);
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Token续期成功", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void setupLocalVideo() {
        if (mRtcEngine == null || svLocal == null || isLocalPreviewStarted) {
            return;
        }

        VideoCanvas localCanvas = new VideoCanvas(
                svLocal,
                VideoCanvas.RENDER_MODE_FIT,
                LOCAL_UID
        );
        mRtcEngine.setupLocalVideo(localCanvas);

        try {
            mRtcEngine.startPreview();
            isLocalPreviewStarted = true;
        } catch (Exception e) {
            e.printStackTrace();
            runOnUiThread(() -> Toast.makeText(this, "预览启动失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
        }
    }

    private void setupRemoteVideo(int uid) {
        mRtcEngine.setupRemoteVideo(new VideoCanvas(svRemote, VideoCanvas.RENDER_MODE_FIT, uid));
        mRemoteUid = uid;
    }

    // 挂断逻辑
    private void hangupCall() {
        isInCall = false;
        stopRinging();

        if (mRtcEngine != null) {
            // 停止预览、清理视频绑定
            mRtcEngine.stopPreview();
            mRtcEngine.setupLocalVideo(new VideoCanvas(null));
            if (mRemoteUid != -1) {
                mRtcEngine.setupRemoteVideo(new VideoCanvas(null, VideoCanvas.RENDER_MODE_FIT, mRemoteUid));
            }
            // 关闭音视频、离开频道
            mRtcEngine.disableVideo();
            mRtcEngine.disableAudio();
            mRtcEngine.leaveChannel();
            mRemoteUid = -1;
            isLocalPreviewStarted = false;
        }

        // 恢复初始UI状态
        runOnUiThread(() -> {
            svLocal.setVisibility(View.GONE);
            svRemote.setVisibility(View.GONE);
            btnAnswer.setVisibility(View.VISIBLE);
            btnReject.setVisibility(View.VISIBLE);
            btnHangup.setVisibility(View.GONE);
        });

        if (foregroundServiceIntent != null) {
            stopService(foregroundServiceIntent);
        }
    }

    // 声网回调
    private final IRtcEngineEventHandler mRtcEventHandler = new IRtcEngineEventHandler() {
        @Override
        public void onJoinChannelSuccess(String channel, int uid, int elapsed) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "已加入频道，等待来电", Toast.LENGTH_SHORT).show());
        }

        @Override
        public void onUserJoined(int uid, int elapsed) {
            // 收到来电，记录对方UID并启动振铃
            mRemoteUid = uid;
            runOnUiThread(() -> startRinging());
        }

        @Override
        public void onUserOffline(int uid, int reason) {
            runOnUiThread(() -> {
                stopRinging();
                String tip = reason == Constants.USER_OFFLINE_QUIT ? "对方已挂断" : "对方网络断开";
                Toast.makeText(MainActivity.this, tip, Toast.LENGTH_SHORT).show();
                mRemoteUid = -1;
                if (isInCall) {
                    hangupCall();
                }
            });
        }

        @Override
        public void onLeaveChannel(RtcStats stats) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "已离开频道", Toast.LENGTH_SHORT).show());
        }

        // Token即将过期续期
        @Override
        public void onTokenPrivilegeWillExpire(String token) {
            super.onTokenPrivilegeWillExpire(token);
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Token即将过期，正在续期...", Toast.LENGTH_SHORT).show());
            renewToken();
        }

        // Token已过期重新获取
        @Override
        public void onRequestToken() {
            super.onRequestToken();
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Token已过期，重新加入频道...", Toast.LENGTH_SHORT).show());
            getTokenFromServer(() -> {
                mRtcEngine.leaveChannel();
                joinChannelForListening();
            });
        }
    };

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.btn_answer) {
            answerCall(); // 接听通话
        } else if (id == R.id.btn_reject) {
            rejectCall(); // 拒绝通话
        } else if (id == R.id.btn_hangup) {
            hangupCall(); // 挂断通话
        }
    }

    // SurfaceHolder回调
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        // 仅在已接听通话时初始化本地预览
        if (isInCall) {
            setupLocalVideo();
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (mRtcEngine != null && isLocalPreviewStarted) {
            mRtcEngine.stopPreview();
            isLocalPreviewStarted = false;
        }
    }

    @Override
    protected void onDestroy() {
        stopRinging();
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (vibrator != null) {
            vibrator.cancel();
        }
        if (foregroundServiceIntent != null) {
            stopService(foregroundServiceIntent);
        }
        if (svLocal != null) {
            svLocal.getHolder().removeCallback(this);
        }
        super.onDestroy();
        if (mRtcEngine != null) {
            mRtcEngine.leaveChannel();
            RtcEngine.destroy();
            mRtcEngine = null;
        }
    }
}