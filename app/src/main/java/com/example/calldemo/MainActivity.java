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

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

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
            Manifest.permission.INTERNET // 新增网络权限（获取Token需要）

    };

    private String currentToken = ""; // 保存当前有效Token

    private Vibrator vibrator;
    private RtcEngine mRtcEngine;
    private SurfaceView svLocal, svRemote;
    private Button btnHangup;
    private int mRemoteUid = -1;
    private PowerManager.WakeLock wakeLock;
    private Intent foregroundServiceIntent;
    // 标记：本地预览是否已启动（避免重复调用）
    private boolean isLocalPreviewStarted = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 1. 检查并申请权限（含相机权限）
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
            // 👉 核心修复：绑定SurfaceHolder.Callback监听Surface创建
            svLocal.getHolder().addCallback(this);
            // 先不调用setupLocalVideo，等surfaceCreated后再执行
            // 先获取Token，再加入频道
            getTokenFromServer(() -> joinChannel());
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "初始化失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    // 检查权限是否全部授予（含相机）
    private boolean checkPermissions() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    // 权限申请结果回调（确保相机权限授予后再初始化）
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
                Toast.makeText(this, "必须授予所有权限（含相机）才能显示本端视频", Toast.LENGTH_LONG).show();
                finish();
            } else {
                recreate(); // 重启Activity，重新初始化视频逻辑
            }
        }
    }

    private void initView() {
        svLocal = findViewById(R.id.sv_local);
        svRemote = findViewById(R.id.sv_remote);
        btnHangup = findViewById(R.id.btn_hangup);
        btnHangup.setOnClickListener(this);

        // 确保SurfaceView可见且有尺寸
        svLocal.setVisibility(View.VISIBLE);
        svLocal.setZOrderOnTop(true); // 👉 新增：确保本地画面在最上层（避免被覆盖）
        svRemote.setVisibility(View.VISIBLE);
    }

    private void initAgoraEngine() throws Exception {
        mRtcEngine = RtcEngine.create(getApplicationContext(), AGORA_APP_ID, mRtcEventHandler);
        // 显式开启视频+音频
        mRtcEngine.enableVideo();
        mRtcEngine.enableAudio();
        mRtcEngine.setEnableSpeakerphone(true);
        mRtcEngine.setChannelProfile(Constants.CHANNEL_PROFILE_COMMUNICATION);
        // 👉 新增：强制开启本地视频采集（部分机型需要显式设置）
        mRtcEngine.muteLocalVideoStream(false);
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

    // 👉 核心修复：只有Surface创建完成后，才初始化本地视频渲染
    private void setupLocalVideo() {
        if (mRtcEngine == null || svLocal == null || isLocalPreviewStarted) {
            return;
        }

        // 初始化本地渲染
        VideoCanvas localCanvas = new VideoCanvas(
                svLocal,
                VideoCanvas.RENDER_MODE_FIT,
                LOCAL_UID
        );
        mRtcEngine.setupLocalVideo(localCanvas);

        // 启动本地预览
        try {
            mRtcEngine.startPreview();
            isLocalPreviewStarted = true;
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "本端视频预览已成功启动", Toast.LENGTH_SHORT).show());
        } catch (Exception e) {
            e.printStackTrace();
            runOnUiThread(() -> Toast.makeText(this, "预览启动失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
        }
    }
/*

    // 从服务端获取Token（POST方式）
    private void getTokenFromServer(Runnable onSuccess) {
        new Thread(() -> {
            try {
                URL url = new URL(SERVER_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                // 设置POST请求
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                // 允许写入请求体
                conn.setDoOutput(true);
                // 设置请求头（JSON格式）
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");

                // 构建请求体参数
                JSONObject requestParams = new JSONObject();
                requestParams.put("uid", LOCAL_UID);
                String paramsStr = requestParams.toString();

                // 写入请求体
                OutputStream os = conn.getOutputStream();
                os.write(paramsStr.getBytes("UTF-8"));
                os.flush();
                os.close();

                // 处理响应
                if (conn.getResponseCode() == 200) {
                    // 读取响应数据
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();
                    conn.disconnect();

                    currentToken = response.toString();
                    // 主线程执行成功回调
                    runOnUiThread(onSuccess);
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Token获取成功", Toast.LENGTH_SHORT).show());


                    // 解析JSON响应
                    JSONObject json = new JSONObject(response.toString());
                    if (json.getInt("code") == 200) {
                        currentToken = json.getString("token");
                        // 主线程执行成功回调
                        runOnUiThread(onSuccess);
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "Token获取成功", Toast.LENGTH_SHORT).show());
                    } else {
                        runOnUiThread(() -> {
                            try {
                                Toast.makeText(MainActivity.this, "Token获取失败：" + json.getString("msg"), Toast.LENGTH_LONG).show();
                            } catch (JSONException e) {
                                throw new RuntimeException(e);
                            }
                        });
                    }
                } else {
                    runOnUiThread(() -> {
                        try {
                            Toast.makeText(MainActivity.this, "服务端连接失败：" + conn.getResponseCode(), Toast.LENGTH_LONG).show();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
                }
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Token获取异常：" + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }
*/

    // 从服务端获取Token（POST方式）
    private void getTokenFromServer(Runnable onSuccess) {
        new Thread(() -> {
            try {

                RtcTokenBuilder2 token = new RtcTokenBuilder2();
                currentToken = token.buildTokenWithUid("585ceb26ea044e649a7a39304d323dc7", "b90fc32735af48019f1a4392c12aee16", "HoneyFamily", LOCAL_UID, RtcTokenBuilder2.Role.ROLE_PUBLISHER, 36000, 36000);

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
    private void joinChannel() {
        // 延迟1秒加入频道，确保初始化完成
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (currentToken.isEmpty()) {
                Toast.makeText(this, "Token为空，无法加入频道", Toast.LENGTH_SHORT).show();
                return;
            }
            mRtcEngine.joinChannel(currentToken, CHANNEL_NAME, null, LOCAL_UID);
            Toast.makeText(this, "正在加入音视频通话...", Toast.LENGTH_SHORT).show();
        }, 1000);
    }

    private void setupRemoteVideo(int uid) {
        mRtcEngine.setupRemoteVideo(new VideoCanvas(svRemote, VideoCanvas.RENDER_MODE_FIT, uid));
        mRemoteUid = uid;
    }

    // 震动逻辑
    private void triggerVibration() {
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire();
        }
        if (vibrator != null && vibrator.hasVibrator()) {
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(3000, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vibrator.vibrate(3000);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    // 声网回调
    private final IRtcEngineEventHandler mRtcEventHandler = new IRtcEngineEventHandler() {
        @Override
        public void onJoinChannelSuccess(String channel, int uid, int elapsed) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "加入通话成功", Toast.LENGTH_SHORT).show());
        }

        @Override
        public void onUserJoined(int uid, int elapsed) {
            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this, "对方已加入通话（UID：" + uid + "）", Toast.LENGTH_SHORT).show();
                setupRemoteVideo(uid);
                triggerVibration();
            });
        }

        @Override
        public void onUserOffline(int uid, int reason) {
            runOnUiThread(() -> {
                String tip = reason == Constants.USER_OFFLINE_QUIT ? "对方已挂断通话" : "对方网络断开";
                Toast.makeText(MainActivity.this, tip, Toast.LENGTH_SHORT).show();
                mRtcEngine.setupRemoteVideo(new VideoCanvas(null, VideoCanvas.RENDER_MODE_FIT, uid));
                mRemoteUid = -1;
                if (wakeLock != null && wakeLock.isHeld()) {
                    wakeLock.release();
                }
            });
        }

        @Override
        public void onLeaveChannel(RtcStats stats) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "已挂断通话", Toast.LENGTH_SHORT).show());
        }

        // 核心：Token即将过期（前30秒）触发续期
        @Override
        public void onTokenPrivilegeWillExpire(String token) {
            super.onTokenPrivilegeWillExpire(token);
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Token即将过期，正在自动续期...", Toast.LENGTH_SHORT).show());
            // 自动获取新Token并续期
            renewToken();
        }

        // 兜底：Token已过期（续期失败时触发）
        @Override
        public void onRequestToken() {
            super.onRequestToken();
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Token已过期，重新获取并加入频道...", Toast.LENGTH_SHORT).show());
            // 重新获取Token并加入频道
            getTokenFromServer(() -> {
                mRtcEngine.leaveChannel();
                joinChannel();
            });
        }
    };

    // 挂断逻辑
    private void hangupCall() {
        if (mRtcEngine != null) {
            mRtcEngine.stopPreview();
            mRtcEngine.setupLocalVideo(new VideoCanvas(null));
            if (mRemoteUid != -1) {
                mRtcEngine.setupRemoteVideo(new VideoCanvas(null, VideoCanvas.RENDER_MODE_FIT, mRemoteUid));
            }
            mRtcEngine.leaveChannel();
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (vibrator != null) {
            vibrator.cancel();
        }
        if (foregroundServiceIntent != null) {
            stopService(foregroundServiceIntent);
        }
        finish();
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.btn_hangup) {
            hangupCall();
        }
    }

    // 👉 核心：SurfaceHolder.Callback 监听Surface创建/销毁
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        // SurfaceView创建完成，才初始化本地视频
        setupLocalVideo();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // Surface尺寸变化时，无需额外操作（声网会自动适配）
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        // Surface销毁时，停止本地预览
        if (mRtcEngine != null && isLocalPreviewStarted) {
            mRtcEngine.stopPreview();
            isLocalPreviewStarted = false;
        }
    }

    @Override
    protected void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (vibrator != null) {
            vibrator.cancel();
        }
        if (foregroundServiceIntent != null) {
            stopService(foregroundServiceIntent);
        }
        // 移除Surface回调，避免内存泄漏
        if (svLocal != null) {
            svLocal.getHolder().removeCallback(this);
        }
        super.onDestroy();
        if (mRtcEngine != null) {
            mRtcEngine.stopPreview();
            mRtcEngine.leaveChannel();
            RtcEngine.destroy();
            mRtcEngine = null;
        }
    }
}