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
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    // 权限请求码
    private static final int PERMISSION_REQUEST_CODE = 101;
    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.VIBRATE,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.INTERNET,
            Manifest.permission.DISABLE_KEYGUARD // 新增锁屏权限
    };

    private String currentToken = ""; // 保存当前有效Token

    // UI组件
    private Vibrator vibrator;
    private RtcEngine mRtcEngine;
    private SurfaceView svLocal, svRemote;
    private Button btnAnswer, btnReject, btnHangup, btnCallSelected;
    private ListView lvUserList;
    private LinearLayout llUserList;
    private ArrayAdapter<String> userListAdapter;
    private Set<Integer> channelUserUids = new HashSet<>(); // 频道内用户UID集合
    private int selectedCallUid = -1; // 选中要呼叫的用户UID

    // 状态标记
    private int mRemoteUid = -1;
    private PowerManager.WakeLock wakeLock;
    private PowerManager.WakeLock screenWakeLock; // 新增屏幕唤醒锁
    private Intent foregroundServiceIntent;
    private boolean isLocalPreviewStarted = false;
    private boolean isInCall = false; // 是否已建立通话
    private boolean isRinging = false; // 是否正在振铃
    private boolean isInitiativeCall = false; // 是否是主动呼叫

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

        // 3. 初始化唤醒锁（修复锁屏振动）
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        // 部分唤醒锁：保持CPU运行
        wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "CallDemo::VibrateWakeLock"
        );
        // 屏幕唤醒锁：点亮屏幕
        screenWakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE,
                "CallDemo::ScreenWakeLock"
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
            // 初始化用户列表适配器
            initUserListAdapter();
            // 先获取Token，再加入频道（仅监听）
            getTokenFromServer(this::joinChannelForListening);
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "初始化失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    // 初始化用户列表适配器
    private void initUserListAdapter() {
        List<String> userList = new ArrayList<>();
        userListAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_single_choice, userList);
        lvUserList.setAdapter(userListAdapter);
        lvUserList.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        // 选中用户监听
        lvUserList.setOnItemClickListener((parent, view, position, id) -> {
            String selectedItem = (String) parent.getItemAtPosition(position);
            if (selectedItem != null && selectedItem.contains("UID:")) {
                selectedCallUid = Integer.parseInt(selectedItem.split(":")[1].trim());
                btnCallSelected.setVisibility(View.VISIBLE);
            }
        });
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
        btnAnswer = findViewById(R.id.btn_answer);
        btnReject = findViewById(R.id.btn_reject);
        btnHangup = findViewById(R.id.btn_hangup);
        btnCallSelected = findViewById(R.id.btn_call_selected);
        lvUserList = findViewById(R.id.lv_user_list);
        llUserList = findViewById(R.id.ll_user_list);

        // 按钮点击事件
        btnAnswer.setOnClickListener(this);
        btnReject.setOnClickListener(this);
        btnHangup.setOnClickListener(this);
        btnCallSelected.setOnClickListener(this);

        // 初始状态
        resetUIState();

        svLocal.setZOrderOnTop(true);
    }

    // 重置UI状态为初始值
    private void resetUIState() {
        btnAnswer.setVisibility(View.VISIBLE);
        btnReject.setVisibility(View.VISIBLE);
        btnHangup.setVisibility(View.GONE);
        btnCallSelected.setVisibility(View.GONE);
        svLocal.setVisibility(View.GONE);
        svRemote.setVisibility(View.GONE);
        llUserList.setVisibility(View.VISIBLE); // 始终显示用户列表
        selectedCallUid = -1;
    }

    private void initAgoraEngine() throws Exception {
        if (mRtcEngine == null) {
            mRtcEngine = RtcEngine.create(getApplicationContext(), AGORA_APP_ID, mRtcEventHandler);
            // 初始化时先关闭音视频
            mRtcEngine.disableVideo();
            mRtcEngine.disableAudio();
            mRtcEngine.setChannelProfile(Constants.CHANNEL_PROFILE_COMMUNICATION);
        }
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
            if (currentToken.isEmpty() || mRtcEngine == null) {
                Toast.makeText(this, "Token为空或引擎未初始化，无法加入频道", Toast.LENGTH_SHORT).show();
                return;
            }
            // 加入频道，但不开启音视频
            mRtcEngine.joinChannel(currentToken, CHANNEL_NAME, null, LOCAL_UID);
            Toast.makeText(this, "已进入通话频道，等待来电/可主动呼叫...", Toast.LENGTH_SHORT).show();
        }, 1000);
    }

    // 主动呼叫指定用户
    private void callSelectedUser() {
        if (selectedCallUid == -1 || selectedCallUid == LOCAL_UID) {
            Toast.makeText(this, "请选择有效的用户进行呼叫", Toast.LENGTH_SHORT).show();
            return;
        }
        if (isInCall) {
            Toast.makeText(this, "当前已有通话，请先挂断", Toast.LENGTH_SHORT).show();
            return;
        }

        isInitiativeCall = true;
        mRemoteUid = selectedCallUid;
        // 主动呼叫时直接开启音视频
        try {
            mRtcEngine.enableVideo();
            mRtcEngine.enableAudio();
            mRtcEngine.setEnableSpeakerphone(true);
            mRtcEngine.muteLocalVideoStream(false);

            // 显示视频画面，切换按钮状态
            svLocal.setVisibility(View.VISIBLE);
            svRemote.setVisibility(View.VISIBLE);
            btnAnswer.setVisibility(View.GONE);
            btnReject.setVisibility(View.GONE);
            btnHangup.setVisibility(View.VISIBLE);
            btnCallSelected.setVisibility(View.GONE);

            // 初始化本地预览
            if (svLocal.getHolder().getSurface().isValid()) {
                setupLocalVideo();
            }

            // 绑定远程视频
            setupRemoteVideo(mRemoteUid);

            // 标记通话已建立
            isInCall = true;
            Toast.makeText(this, "已呼叫用户 UID：" + mRemoteUid, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "呼叫失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
            isInitiativeCall = false;
        }
    }

    // 接听通话
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
            btnCallSelected.setVisibility(View.GONE);

            // 4. 初始化本地预览
            if (svLocal.getHolder().getSurface().isValid()) {
                setupLocalVideo();
            }

            // 5. 绑定远程视频
            setupRemoteVideo(mRemoteUid);

            // 6. 标记通话已建立
            isInCall = true;
            Toast.makeText(this, "已接听通话（UID：" + mRemoteUid + "）", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "接听失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // 拒绝通话
    private void rejectCall() {
        stopRinging();
        mRemoteUid = -1;
        Toast.makeText(this, "已拒绝通话", Toast.LENGTH_SHORT).show();
    }

    // 启动振铃（修复锁屏振动）
    private void startRinging() {
        if (isRinging) {
            return;
        }
        isRinging = true;

        // 1. 唤醒屏幕（修复锁屏不振动核心）
        if (screenWakeLock != null && !screenWakeLock.isHeld()) {
            screenWakeLock.acquire(30*1000); // 持锁30秒
        }
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(30*1000);
        }

        // 2. 循环振铃
        if (vibrator != null && vibrator.hasVibrator()) {
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    VibrationEffect effect = VibrationEffect.createWaveform(
                            new long[]{0, 1000, 2000},
                            0 // 无限循环
                    );
                    vibrator.vibrate(effect);
                } else {
                    vibrator.vibrate(new long[]{0, 1000, 2000}, 0);
                }
            } catch (Exception e) {
                e.printStackTrace();
                Toast.makeText(this, "振动失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
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
        if (screenWakeLock != null && screenWakeLock.isHeld()) {
            screenWakeLock.release();
        }
    }

    // 从服务端获取Token
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

    // 挂断逻辑（核心：重置状态，支持重新接听）
    private void hangupCall() {
        isInCall = false;
        isInitiativeCall = false;
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

            // 重置状态
            mRemoteUid = -1;
            isLocalPreviewStarted = false;
        }

        // 恢复初始UI状态
        runOnUiThread(this::resetUIState);

        // 核心修复：重新初始化，支持再次接听/呼叫
        try {
            // 重新初始化引擎（如果已销毁）
            if (mRtcEngine == null) {
                initAgoraEngine();
                setupVideoConfig();
            }
            // 重新获取Token并加入频道
            getTokenFromServer(this::joinChannelForListening);
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "重置失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }

        Toast.makeText(this, "已挂断通话，可重新接听/呼叫", Toast.LENGTH_SHORT).show();
    }

    // 更新频道用户列表
    private void updateChannelUserList() {
        runOnUiThread(() -> {
            List<String> userList = new ArrayList<>();
            for (int uid : channelUserUids) {
                if (uid != LOCAL_UID) { // 排除自己
                    userList.add("用户 UID: " + uid);
                }
            }
            userListAdapter.clear();
            userListAdapter.addAll(userList);
            userListAdapter.notifyDataSetChanged();
        });
    }

    // 声网回调
    private final IRtcEngineEventHandler mRtcEventHandler = new IRtcEngineEventHandler() {
        @Override
        public void onJoinChannelSuccess(String channel, int uid, int elapsed) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "已加入频道", Toast.LENGTH_SHORT).show());
            // 加入频道后添加自己到用户列表
            channelUserUids.add(LOCAL_UID);
            updateChannelUserList();
        }

        @Override
        public void onUserJoined(int uid, int elapsed) {
            // 新增用户到列表
            channelUserUids.add(uid);
            updateChannelUserList();

            // 被动来电：非主动呼叫且未在通话中时启动振铃
            if (!isInitiativeCall && !isInCall) {
                mRemoteUid = uid;
                runOnUiThread(() -> startRinging());
            }
        }

        @Override
        public void onUserOffline(int uid, int reason) {
            // 移除离线用户
            channelUserUids.remove(uid);
            updateChannelUserList();

            runOnUiThread(() -> {
                stopRinging();
                String tip = reason == Constants.USER_OFFLINE_QUIT ? "对方已挂断" : "对方网络断开";
                Toast.makeText(MainActivity.this, tip, Toast.LENGTH_SHORT).show();

                // 如果是当前通话对象离线，挂断并重置
                if (uid == mRemoteUid && isInCall) {
                    hangupCall();
                }
                mRemoteUid = -1;
            });
        }

        @Override
        public void onLeaveChannel(RtcStats stats) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "已离开频道", Toast.LENGTH_SHORT).show());
            channelUserUids.clear();
            updateChannelUserList();
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
        } else if (id == R.id.btn_call_selected) {
            callSelectedUser(); // 主动呼叫选中用户
        }
    }

    // SurfaceHolder回调
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
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
        // 释放唤醒锁
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (screenWakeLock != null && screenWakeLock.isHeld()) {
            screenWakeLock.release();
        }
        // 释放振动器
        if (vibrator != null) {
            vibrator.cancel();
        }
        // 停止前台服务
        if (foregroundServiceIntent != null) {
            stopService(foregroundServiceIntent);
        }
        // 移除Surface回调
        if (svLocal != null) {
            svLocal.getHolder().removeCallback(this);
        }
        // 销毁引擎
        if (mRtcEngine != null) {
            mRtcEngine.leaveChannel();
            RtcEngine.destroy();
            mRtcEngine = null;
        }
        super.onDestroy();
    }
}