package com.techsurf.supervpn;

import android.content.Context;
import android.content.SharedPreferences;

import com.techsurf.supervpn.model.Server;

import static com.techsurf.supervpn.Utils.getImgURL;

public class SharedPreference {

    private static final String APP_PREFS_NAME = "SuperVPNPreference";

    private SharedPreferences mPreference;
    private SharedPreferences.Editor mPrefEditor;
    private Context context;

    private static final String SERVER_COUNTRY = "server_country";
    private static final String SERVER_FLAG = "server_flag";
    private static final String SERVER_OVPN = "server_ovpn";
    private static final String SERVER_OVPN_USER = "server_ovpn_user";
    private static final String SERVER_OVPN_PASSWORD = "server_ovpn_password";

    public SharedPreference(Context context) {
        this.mPreference = context.getSharedPreferences(APP_PREFS_NAME, Context.MODE_PRIVATE);
        this.mPrefEditor = mPreference.edit();
        this.context = context;
    }

    /**
     * Save server details
     * @param server details of ovpn server
     */
    public void saveServer(Server server){
        mPrefEditor.putString(SERVER_COUNTRY, server.getCountry());
        mPrefEditor.putString(SERVER_FLAG, server.getFlagUrl());
        mPrefEditor.putString(SERVER_OVPN, server.getOvpn());
        mPrefEditor.putString(SERVER_OVPN_USER, server.getOvpnUserName());
        mPrefEditor.putString(SERVER_OVPN_PASSWORD, server.getOvpnUserPassword());
        mPrefEditor.commit();
    }

    /**
     * Get server data from shared preference
     * @return server model object
     */
    public Server getServer() {

        // Enforce allowed servers only (US asset or remote URL)
        String storedOvpn = mPreference.getString(SERVER_OVPN, "https://supervpn-a644d.web.app/us.ovpn");
        boolean isAllowed =
                "us.ovpn".equals(storedOvpn) ||
                (storedOvpn != null && (storedOvpn.startsWith("http://") || storedOvpn.startsWith("https://")));
        if (!isAllowed) {
            Server fallback = new Server(
                    "United States",
                    getImgURL(R.drawable.usa_flag),
                    "https://supervpn-a644d.web.app/us.ovpn",
                    "freeopenvpn",
                    "416248023"
            );
            // Persist fallback so UI and future reads align
            saveServer(fallback);
            return fallback;
        }

        Server server = new Server(
                mPreference.getString(SERVER_COUNTRY,"United States"),
                mPreference.getString(SERVER_FLAG,getImgURL(R.drawable.usa_flag)),
                storedOvpn,
                mPreference.getString(SERVER_OVPN_USER,"freeopenvpn"),
                mPreference.getString(SERVER_OVPN_PASSWORD,"416248023")
        );

        return server;
    }
}
