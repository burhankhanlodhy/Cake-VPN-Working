package com.techsurf.supervpn.view;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.VpnService;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.databinding.DataBindingUtil;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.techsurf.supervpn.CheckInternetConnection;
import com.techsurf.supervpn.R;
import com.techsurf.supervpn.SharedPreference;
import com.techsurf.supervpn.databinding.FragmentMainBinding;
import com.techsurf.supervpn.interfaces.ChangeServer;
import com.techsurf.supervpn.model.Server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

import de.blinkt.openvpn.OpenVpnApi;
import de.blinkt.openvpn.core.OpenVPNService;
import de.blinkt.openvpn.core.OpenVPNThread;
import de.blinkt.openvpn.core.VpnStatus;

import static android.app.Activity.RESULT_OK;

public class MainFragment extends Fragment implements View.OnClickListener, ChangeServer {

    private static final String TAG = "SuperVPN";
    private Server server;
    private CheckInternetConnection connection;

    private OpenVPNThread vpnThread = new OpenVPNThread();
    private OpenVPNService vpnService = new OpenVPNService();
    boolean vpnStart = false;
    private SharedPreference preference;

    private FragmentMainBinding binding;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        binding = DataBindingUtil.inflate(inflater, R.layout.fragment_main, container, false);

        View view = binding.getRoot();
        initializeAll();

        return view;
    }

    /**
     * Initialize all variable and object
     */
    private void initializeAll() {
        preference = new SharedPreference(getContext());
        server = preference.getServer();

        // Update current selected server icon
        updateCurrentServerIcon(server.getFlagUrl());

        connection = new CheckInternetConnection();
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        binding.vpnBtn.setOnClickListener(this);

        // Checking is vpn already running or not
        isServiceRunning();
        VpnStatus.initLogCache(getActivity().getCacheDir());
    }

    /**
     * @param v: click listener view
     */
    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.vpnBtn:
                // Vpn is running, user would like to disconnect current connection.
                if (vpnStart) {
                    confirmDisconnect();
                }else {
                    prepareVpn();
                }
        }
    }

    /**
     * Show show disconnect confirm dialog
     */
    public void confirmDisconnect(){
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        builder.setMessage(getActivity().getString(R.string.connection_close_confirm));

        builder.setPositiveButton(getActivity().getString(R.string.yes), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
                stopVpn();
            }
        });
        builder.setNegativeButton(getActivity().getString(R.string.no), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
                // User cancelled the dialog
            }
        });

        // Create the AlertDialog
        AlertDialog dialog = builder.create();
        dialog.show();
    }

    /**
     * Prepare for vpn connect with required permission
     */
    private void prepareVpn() {
        if (!vpnStart) {
            if (getInternetStatus()) {

                // Checking permission for network monitor
                Intent intent = VpnService.prepare(getContext());

                if (intent != null) {
                    startActivityForResult(intent, 1);
                } else startVpn();//have already permission

                // Update confection status
                status("connecting");

            } else {

                // No internet connection available
                showToast("you have no internet connection !!");
            }

        } else if (stopVpn()) {

            // VPN is stopped, show a Toast message.
            showToast("Disconnect Successfully");
        }
    }

    /**
     * Stop vpn
     * @return boolean: VPN status
     */
    public boolean stopVpn() {
        try {
            vpnThread.stop();

            status("connect");
            vpnStart = false;
            return true;
        } catch (Exception e) {
            e.printStackTrace();
        }

        return false;
    }

    /**
     * Taking permission for network access
     */
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode == RESULT_OK) {

            //Permission granted, start the VPN
            startVpn();
        } else {
            showToast("Permission Deny !! ");
        }
    }

    /**
     * Internet connection status.
     */
    public boolean getInternetStatus() {
        return connection.netCheck(getContext());
    }

    /**
     * Get service status
     */
    public void isServiceRunning() {
        setStatus(vpnService.getStatus());
    }

    /**
     * Start the VPN
     */
    private void startVpn() {
        try {
            String ovpnPath = server.getOvpn();
            if (ovpnPath != null && (ovpnPath.startsWith("http://") || ovpnPath.startsWith("https://"))) {
                // Fetch remote .ovpn on background thread
                new Thread(() -> {
                    try {
                        URL url = new URL(ovpnPath);
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setConnectTimeout(10000);
                        conn.setReadTimeout(15000);
                        conn.setRequestMethod("GET");
                        int code = conn.getResponseCode();
                        InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                        String config = readAll(in);
                        if (code >= 200 && code < 300 && config != null && !config.trim().isEmpty()) {
                            getActivity().runOnUiThread(() -> {
                                try {
                                    OpenVpnApi.startVpn(getContext(), config, server.getCountry(), server.getOvpnUserName(), server.getOvpnUserPassword());
                                    Log.i(TAG, "Connected using firebase");
                                    vpnStart = true;
                                } catch (RemoteException e) {
                                    e.printStackTrace();
                                    showToast("Failed to start VPN");
                                }
                            });
                        } else {
                            // If Japan URL, do not fallback to asset
                            if (ovpnPath.endsWith("/japan.ovpn")) {
                                Log.w(TAG, "Japan config fetch failed, not falling back to asset");
                                getActivity().runOnUiThread(() -> showToast("Failed to fetch Japan config"));
                            } else {
                                // Fallback to local asset using last path segment
                                fallbackToAsset(ovpnPath);
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        if (ovpnPath.endsWith("/japan.ovpn")) {
                            Log.w(TAG, "Japan config fetch error, not falling back to asset");
                            getActivity().runOnUiThread(() -> showToast("Failed to fetch Japan config"));
                        } else {
                            fallbackToAsset(ovpnPath);
                        }
                    }
                }).start();
                return;
            }

            // Local asset flow (original)
            InputStream conf = getActivity().getAssets().open(ovpnPath);
            InputStreamReader isr = new InputStreamReader(conf);
            BufferedReader br = new BufferedReader(isr);
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            String config = sb.toString();
            OpenVpnApi.startVpn(getContext(), config, server.getCountry(), server.getOvpnUserName(), server.getOvpnUserPassword());
            Log.i(TAG, "Connected using asset files");
            vpnStart = true;

        } catch (IOException | RemoteException e) {
            e.printStackTrace();
        }
    }

    private void fallbackToAsset(String pathHint) {
        try {
            String assetName = pathHint == null ? null : pathHint.substring(pathHint.lastIndexOf('/') + 1);
            if (assetName == null || assetName.isEmpty()) assetName = "us.ovpn"; // safe default
            InputStream conf = getActivity().getAssets().open(assetName);
            InputStreamReader isr = new InputStreamReader(conf);
            BufferedReader br = new BufferedReader(isr);
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            String config = sb.toString();
            String finalConfig = config;
            getActivity().runOnUiThread(() -> {
                try {
                    OpenVpnApi.startVpn(getContext(), finalConfig, server.getCountry(), server.getOvpnUserName(), server.getOvpnUserPassword());
                    Log.i(TAG, "Connected using asset files");
                    vpnStart = true;
                } catch (RemoteException e) {
                    e.printStackTrace();
                    showToast("Failed to start VPN");
                }
            });
        } catch (Exception ex) {
            ex.printStackTrace();
            getActivity().runOnUiThread(() -> showToast("Failed to load VPN config"));
        }
    }

    private String readAll(InputStream in) throws IOException {
        if (in == null) return null;
        BufferedReader br = new BufferedReader(new InputStreamReader(in));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /**
     * Status change with corresponding vpn connection status
     * @param connectionState
     */
    public void setStatus(String connectionState) {
        if (connectionState!= null)
        switch (connectionState) {
            case "DISCONNECTED":
                status("connect");
                vpnStart = false;
                vpnService.setDefaultStatus();
//                binding.logTv.setText("");
                break;
            case "CONNECTED":
                vpnStart = true;// it will use after restart this activity
                status("connected");
//                binding.logTv.setText("");
                break;
            case "WAIT":
//                binding.logTv.setText("waiting for server connection!!");
                break;
            case "AUTH":
//                binding.logTv.setText("server authenticating!!");
                break;
            case "RECONNECTING":
                status("connecting");
//                binding.logTv.setText("Reconnecting...");
                break;
            case "NONETWORK":
//                binding.logTv.setText("No network connection");
                break;
        }

    }

    /**
     * Change button background color and text
     * @param status: VPN current status
     */
    public void status(String status) {

        if (status.equals("connect")) {
            // Default state: show disconnect icon (as requested)
            binding.vpnBtn.setImageResource(R.drawable.disconnect);
            // Update banner status text
            binding.statusTv.setText("Disconnected");
        } else if (status.equals("connecting")) {
            // While connecting, show connect button icon
            binding.vpnBtn.setImageResource(R.drawable.connectbutton);
            binding.statusTv.setText("Connecting...");
        } else if (status.equals("connected")) {
            // Connected: show connect button icon
            binding.vpnBtn.setImageResource(R.drawable.connectbutton);
            binding.statusTv.setText("Connected");
        } else if (status.equals("tryDifferentServer")) {
            // keep connect icon to indicate action to connect to another server
            binding.vpnBtn.setImageResource(R.drawable.connectbutton);
            binding.statusTv.setText("Disconnected");
        } else if (status.equals("loading")) {
            // loading: keep default
            binding.vpnBtn.setImageResource(R.drawable.disconnect);
            binding.statusTv.setText("Loading...");
        } else if (status.equals("invalidDevice")) {
            binding.vpnBtn.setImageResource(R.drawable.disconnect);
            binding.statusTv.setText("Disconnected");
        } else if (status.equals("authenticationCheck")) {
            binding.statusTv.setText("Connecting...");
        }

    }

    /**
     * Receive broadcast message
     */
    BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                setStatus(intent.getStringExtra("state"));
            } catch (Exception e) {
                e.printStackTrace();
            }

            try {

                String duration = intent.getStringExtra("duration");
                String lastPacketReceive = intent.getStringExtra("lastPacketReceive");
                String byteIn = intent.getStringExtra("byteIn");
                String byteOut = intent.getStringExtra("byteOut");

                if (duration == null) duration = "00:00:00";
                if (lastPacketReceive == null) lastPacketReceive = "0";
                if (byteIn == null) byteIn = " ";
                if (byteOut == null) byteOut = " ";
                updateConnectionStatus(duration, lastPacketReceive, byteIn, byteOut);
            } catch (Exception e) {
                e.printStackTrace();
            }

        }
    };

    /**
     * Update status UI
     * @param duration: running time
     * @param lastPacketReceive: last packet receive time
     * @param byteIn: incoming data
     * @param byteOut: outgoing data
     */
    public void updateConnectionStatus(String duration, String lastPacketReceive, String byteIn, String byteOut) {
        // Update time line in banner (format as 00 : 00 : 00)
        String formattedDuration = duration == null ? "00 : 00 : 00" : duration.replace(":", " : ");
        binding.timeTv.setText(formattedDuration);

        // New: overlay bytes values inside the speed images with two spaces padding
        if (byteIn == null) byteIn = "";
        if (byteOut == null) byteOut = "";
        binding.downloadBytesTv.setText("  " + byteIn + "  ");
        binding.uploadBytesTv.setText("  " + byteOut + "  ");

        // Keep existing detailed labels below with two spaces after the colon
//        binding.lastPacketReceiveTv.setText("Packet Received: " + lastPacketReceive + " second ago");
//        binding.byteInTv.setText("Bytes In:  " + byteIn);
//        binding.byteOutTv.setText("Bytes OUT:  " + byteOut);
    }

    /**
     * Show toast message
     * @param message: toast message
     */
    public void showToast(String message) {
        Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
    }

    /**
     * VPN server country icon change
     * @param serverIcon: icon URL
     */
    public void updateCurrentServerIcon(String serverIcon) {
        // No-op: bottom flag icon removed from the main page UI
    }

    /**
     * Change server when user select new server
     * @param server ovpn server details
     */
    @Override
    public void newServer(Server server) {
        this.server = server;
        updateCurrentServerIcon(server.getFlagUrl());

        // Stop previous connection
        if (vpnStart) {
            stopVpn();
        }

        prepareVpn();
    }

    @Override
    public void onResume() {
        LocalBroadcastManager.getInstance(getActivity()).registerReceiver(broadcastReceiver, new IntentFilter("connectionState"));

        if (server == null) {
            server = preference.getServer();
        }
        super.onResume();
    }

    @Override
    public void onPause() {
        LocalBroadcastManager.getInstance(getActivity()).unregisterReceiver(broadcastReceiver);
        super.onPause();
    }

    /**
     * Save current selected server on local shared preference
     */
    @Override
    public void onStop() {
        if (server != null) {
            preference.saveServer(server);
        }

        super.onStop();
    }
}
