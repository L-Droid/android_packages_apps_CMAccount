package com.cyanogenmod.id.auth;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response.ErrorListener;
import com.android.volley.Response.Listener;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.RequestFuture;
import com.android.volley.toolbox.Volley;
import com.cyanogenmod.id.CMID;
import com.cyanogenmod.id.api.AuthTokenRequest;
import com.cyanogenmod.id.api.AuthTokenResponse;
import com.cyanogenmod.id.api.CreateProfileRequest;
import com.cyanogenmod.id.api.CreateProfileResponse;
import com.cyanogenmod.id.api.PingRequest;
import com.cyanogenmod.id.api.PingResponse;
import com.cyanogenmod.id.api.PingService;
import com.cyanogenmod.id.api.ProfileAvailableRequest;
import com.cyanogenmod.id.api.ProfileAvailableResponse;
import com.cyanogenmod.id.api.ReportLocationRequest;
import com.cyanogenmod.id.api.SendStartingWipeRequest;
import com.cyanogenmod.id.api.SetHandshakeRequest;
import com.cyanogenmod.id.provider.CMIDProvider;
import com.cyanogenmod.id.util.CMIDUtils;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.admin.DevicePolicyManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import java.util.concurrent.ExecutionException;

public class AuthClient {

    private static final String TAG = AuthClient.class.getSimpleName();
    private static final int API_VERSION = 1;
    private static final String API_ROOT = "/api/v" + API_VERSION;
    private static final String PROFILE_METHOD = "/profile";
    private static final String REGISTER_METHOD = "/register";
    private static final String AVAILABLE_METHOD = "/available";
    private static final String DEVICE_METHOD = "/device";
    private static final String PING_METHOD = "/ping";
    private static final String AUTH_METHOD = "/auth";
    private static final String SET_HANDSHAKE_METHOD = "/set_handshake_secret";
    private static final String SEND_WIPE_STARTED_METHOD = "/wipe_started";
    private static final String REPORT_LOCATION_METHOD = "/report_location";
    private static final String CMID_URI = "https://cmid-devel.appspot.com";
    private static final String SERVER_URI = getServerURI();

    public static final String AUTH_URI = SERVER_URI + "/oauth2/token";
    public static final String REGISTER_PROFILE_URI = SERVER_URI + API_ROOT + PROFILE_METHOD + REGISTER_METHOD;
    public static final String PROFILE_AVAILABLE_URI = SERVER_URI + API_ROOT + PROFILE_METHOD + AVAILABLE_METHOD;
    public static final String PING_URI = SERVER_URI + API_ROOT + DEVICE_METHOD + PING_METHOD;
    public static final String REPORT_LOCATION_URI = SERVER_URI + API_ROOT + DEVICE_METHOD + REPORT_LOCATION_METHOD;
    public static final String SET_HANDSHAKE_URI = SERVER_URI + API_ROOT + AUTH_METHOD + SET_HANDSHAKE_METHOD;
    public static final String SEND_WIPE_STARTED_URI = SERVER_URI + API_ROOT + DEVICE_METHOD + SEND_WIPE_STARTED_METHOD;

    private static final String CLIENT_ID = "8001";
    private static final String SECRET = "b93bb90299bb46f3bafdd6ca630c8f3c";

    public static final String ENCODED_ID_SECRET = new String(Base64.encode((CLIENT_ID + ":" + SECRET).getBytes(), Base64.NO_WRAP));

    private RequestQueue mRequestQueue;

    private static AuthClient sInstance;
    private Context mContext;
    private AccountManager mAccountManager;

    private Request<?> mInFlightPingRequest;
    private Request<?> mInFlightHandshakeRequest;
    private Request<?> mInFlightLocationRequest;
    private Request<?> mInFlightTokenRequest;
    private Request<?> mInFlightStartWipeRequest;
    private Request<?> mInRefreshTokenRequest;

    private AuthClient(Context context) {
        mContext = context.getApplicationContext();
        mAccountManager = AccountManager.get(mContext);
        mRequestQueue = Volley.newRequestQueue(mContext);
    }

    public static final AuthClient getInstance(Context context) {
        if (sInstance == null) sInstance = new AuthClient(context);
        return sInstance;
    }

    public Request<?> login(String username, String password, Listener<AuthTokenResponse> listener, ErrorListener errorListener) {
        return mRequestQueue.add(new AuthTokenRequest(username, password, listener, errorListener));
    }

    public AuthTokenResponse blockingRefreshAccessToken(String refreshToken) throws VolleyError {
        RequestFuture<AuthTokenResponse> future = RequestFuture.newFuture();
        if (mInRefreshTokenRequest != null) {
            mInRefreshTokenRequest.cancel();
            mInRefreshTokenRequest = null;
        }
        mInRefreshTokenRequest = mRequestQueue.add(new AuthTokenRequest(refreshToken, future, future));
        try {
            AuthTokenResponse response = future.get();
            return response;
        } catch (InterruptedException e) {
            throw new VolleyError(e);
        } catch (ExecutionException e) {
            throw new VolleyError(e);
        } finally {
            mInRefreshTokenRequest = null;
        }
    }

    public Request<?> refreshAccessToken(String refreshToken, Listener<AuthTokenResponse> listener, ErrorListener errorListener) {
        return mRequestQueue.add(new AuthTokenRequest(refreshToken, listener, errorListener));
    }

    public Request<?> createProfile(String firstName, String lastName, String email, String username, String password, boolean termsOfService,
            Listener<CreateProfileResponse> listener, ErrorListener errorListener) {
        return mRequestQueue.add(new CreateProfileRequest(firstName, lastName, email, username, password, termsOfService, listener, errorListener));
    }

    public Request<?> checkProfile(String email, String username, Listener<ProfileAvailableResponse> listener, ErrorListener errorListener) {
        return mRequestQueue.add(new ProfileAvailableRequest(email, username, listener, errorListener));
    }

    public void pingService(final Listener<PingResponse> listener, final ErrorListener errorListener) {
        final Account account = CMIDUtils.getCMIDAccount(mContext);
        if (account == null) {
            throw new IllegalStateException("No CMID account configured");
        }
        final TokenCallback callback = new TokenCallback() {
            @Override
            public void onTokenReceived(final String token) {
                if (mInFlightPingRequest != null) {
                    mInFlightPingRequest.cancel();
                    mInFlightPingRequest = null;
                }
                mInFlightPingRequest = mRequestQueue.add(new PingRequest(mContext, CMIDUtils.getUniqueDeviceId(mContext), token, getCarrierName(),
                        new Listener<PingResponse>() {
                            @Override
                            public void onResponse(PingResponse pingResponse) {
                                mInFlightPingRequest = null;
                                if (CMID.DEBUG) Log.d(TAG, "pingService onResponse() : " + pingResponse.getStatusCode());
                                if (listener != null) {
                                    listener.onResponse(pingResponse);
                                }
                            }
                        },
                        new ErrorListener() {
                            @Override
                            public void onErrorResponse(VolleyError volleyError) {
                                mInFlightPingRequest = null;
                                int statusCode = volleyError.networkResponse.statusCode;
                                if (CMID.DEBUG) Log.d(TAG, "pingService onErrorResponse() : " + statusCode);
                                if (statusCode == 401) {
                                    expireToken(mAccountManager, account);
                                    pingService(listener, errorListener);
                                }
                            }
                        }));
            }

            @Override
            public void onError(VolleyError error) {
                if (CMID.DEBUG) {
                    Log.d(TAG, "pingService onError(): ");
                    error.printStackTrace();
                }
                if (errorListener != null) {
                    errorListener.onErrorResponse(error);
                }
            }
        };

        doTokenRequest(account, callback);
    }

    public void reportLocation(final double latitude, final double longitude, final float accuracy, final Listener<Integer> listener, final ErrorListener errorListener) {
        final Account account = CMIDUtils.getCMIDAccount(mContext);
        if (account == null) {
            throw new IllegalStateException("No CMID account configured");
        }

        final TokenCallback callback = new TokenCallback() {
            @Override
            public void onTokenReceived(final String token) {
                if (mInFlightLocationRequest != null) {
                    mInFlightLocationRequest.cancel();
                    mInFlightLocationRequest = null;
                }
                mInFlightLocationRequest = mRequestQueue.add(new ReportLocationRequest(CMIDUtils.getUniqueDeviceId(mContext), token, latitude, longitude, accuracy,
                        new Listener<Integer>() {
                            @Override
                            public void onResponse(Integer integer) {
                                mInFlightLocationRequest = null;
                                if (listener != null) {
                                    listener.onResponse(integer);
                                }
                            }
                        },
                        new ErrorListener() {
                            @Override
                            public void onErrorResponse(VolleyError volleyError) {
                                mInFlightLocationRequest = null;
                                int statusCode = volleyError.networkResponse.statusCode;
                                if (CMID.DEBUG) Log.d(TAG, "reportLocation onErrorResponse() : " + statusCode);
                                if (statusCode == 401) {
                                    expireToken(mAccountManager, account);
                                    reportLocation(latitude, longitude, accuracy, listener, errorListener);
                                }
                            }
                        }));
            }

            @Override
            public void onError(VolleyError error) {
                if (errorListener != null) {
                    errorListener.onErrorResponse(error);
                }
            }
        };

        doTokenRequest(account, callback);
    }

    public void sendHandshakeSecret(final String command, final String secret, final Listener<Integer> listener, final ErrorListener errorListener) {
        final Account account = CMIDUtils.getCMIDAccount(mContext);
        if (account == null) {
            throw new IllegalStateException("No CMID account configured");
        }
        final TokenCallback callback = new TokenCallback() {
            @Override
            public void onTokenReceived(String token) {
                if (mInFlightHandshakeRequest != null) {
                    mInFlightHandshakeRequest.cancel();
                    mInFlightHandshakeRequest = null;
                }
                mInFlightHandshakeRequest = mRequestQueue.add(new SetHandshakeRequest(CMIDUtils.getUniqueDeviceId(mContext), token, command, secret,
                        new Listener<Integer>() {
                            @Override
                            public void onResponse(Integer integer) {
                                mInFlightHandshakeRequest = null;
                                if (listener != null) {
                                    listener.onResponse(integer);
                                }
                            }
                        },
                        new ErrorListener() {
                            @Override
                            public void onErrorResponse(VolleyError volleyError) {
                                mInFlightHandshakeRequest = null;
                                int statusCode = volleyError.networkResponse.statusCode;
                                if (CMID.DEBUG) Log.d(TAG, "sendHandshakeSecret onErrorResponse() : " + statusCode);
                                if (statusCode == 401) {
                                    expireToken(mAccountManager, account);
                                    sendHandshakeSecret(command, secret, listener, errorListener);
                                }
                            }
                        }));
            }

            @Override
            public void onError(VolleyError error) {
                if (errorListener != null) {
                    errorListener.onErrorResponse(error);
                }
            }
        };

        doTokenRequest(account, callback);
    }

    public void setWipeStartedCommand(final Listener<Integer> listener, final ErrorListener errorListener) {
        final Account account = CMIDUtils.getCMIDAccount(mContext);
        if (account == null) {
            throw new IllegalStateException("No CMID account configured");
        }
        final TokenCallback callback = new TokenCallback() {
            @Override
            public void onTokenReceived(String token) {
                if (mInFlightStartWipeRequest != null) {
                    mInFlightStartWipeRequest.cancel();
                    mInFlightStartWipeRequest = null;
                }
                mInFlightStartWipeRequest = mRequestQueue.add(new SendStartingWipeRequest(CMIDUtils.getUniqueDeviceId(mContext), token,
                        new Listener<Integer>() {
                            @Override
                            public void onResponse(Integer integer) {
                                mInFlightStartWipeRequest = null;
                                if (listener != null) {
                                    listener.onResponse(integer);
                                }
                            }
                        },
                        new ErrorListener() {
                            @Override
                            public void onErrorResponse(VolleyError volleyError) {
                                mInFlightStartWipeRequest = null;
                                int statusCode = volleyError.networkResponse.statusCode;
                                if (CMID.DEBUG) Log.d(TAG, "setWipeStartedCommand onErrorResponse() : " + statusCode);
                                if (statusCode == 401) {
                                    expireToken(mAccountManager, account);
                                    setWipeStartedCommand(listener, errorListener);
                                }
                            }
                        }));
            }

            @Override
            public void onError(VolleyError error) {
                if (errorListener != null) {
                    errorListener.onErrorResponse(error);
                }
            }
        };

        doTokenRequest(account, callback);
    }

    public void addLocalAccount(AccountManager accountManager, Account account, AuthTokenResponse response) {
        accountManager.addAccountExplicitly(account, null, null);
        updateLocalAccount(accountManager, account, response);
        PingService.pingServer(mContext);
    }

    public void updateLocalAccount(AccountManager accountManager, Account account, AuthTokenResponse response) {
        accountManager.setUserData(account, CMID.AUTHTOKEN_TYPE_ACCESS, response.getAccessToken());
        accountManager.setAuthToken(account, CMID.AUTHTOKEN_TYPE_ACCESS, response.getAccessToken());
        if (!TextUtils.isEmpty(response.getRefreshToken())) {
            accountManager.setUserData(account, CMID.AUTHTOKEN_TYPE_REFRESH, response.getRefreshToken());
        }
        accountManager.setUserData(account, CMID.AUTHTOKEN_EXPIRES_IN, String.valueOf(System.currentTimeMillis() + (Long.valueOf(response.getExpiresIn()) * 1000)));
        if (CMID.DEBUG) {
            Log.d(TAG, "Access token Expires in = " + (Long.valueOf(response.getExpiresIn()) * 1000) + "ms");
        }
    }

    private void doTokenRequest(final Account account, final TokenCallback tokenCallback) {
        final String currentToken = mAccountManager.peekAuthToken(account, CMID.AUTHTOKEN_TYPE_ACCESS);
        if (CMID.DEBUG) Log.d(TAG, "doTokenRequest() peekAuthToken:  " + currentToken);
        if (isTokenExpired(mAccountManager, account) || currentToken == null) {
            if (CMID.DEBUG) Log.d(TAG, "doTokenRequest() isTokenExpired:  " + "true");
            mAccountManager.invalidateAuthToken(CMID.ACCOUNT_TYPE_CMID, currentToken);
            final String refreshToken = mAccountManager.getUserData(account, CMID.AUTHTOKEN_TYPE_REFRESH);
            if (mInFlightTokenRequest != null) {
                mInFlightTokenRequest.cancel();
                mInFlightTokenRequest = null;
            }
            mInFlightTokenRequest = refreshAccessToken(refreshToken,
                    new Listener<AuthTokenResponse>() {
                        @Override
                        public void onResponse(AuthTokenResponse authTokenResponse) {
                            mInFlightTokenRequest = null;
                            if (CMID.DEBUG) Log.d(TAG, "refreshAccessToken() onResponse token:  " + authTokenResponse.getAccessToken());
                            updateLocalAccount(mAccountManager, account, authTokenResponse);
                            tokenCallback.onTokenReceived(authTokenResponse.getAccessToken());
                        }
                    },
                    new ErrorListener() {
                        @Override
                        public void onErrorResponse(VolleyError volleyError) {
                            mInFlightTokenRequest = null;
                            if (CMID.DEBUG) Log.d(TAG, "refreshAccessToken() onErrorResponse:  " + volleyError.networkResponse.statusCode);
                            tokenCallback.onError(volleyError);
                        }
                    });
        } else {
            if (CMID.DEBUG) Log.d(TAG, "doTokenRequest() returning cached token : " + currentToken);
            tokenCallback.onTokenReceived(currentToken);
        }
    }

    private String getCarrierName() {
        TelephonyManager manager = (TelephonyManager)mContext.getSystemService(Context.TELEPHONY_SERVICE);
        return manager.getNetworkOperatorName();
    }

    private static String getServerURI() {
        String uri = CMID.DEBUG ? SystemProperties.get("cmid.uri") : CMID_URI;
        String cmidUri = (uri == null || uri.length() == 0) ? CMID_URI : uri;
        if (CMID.DEBUG) Log.d(TAG, "Using cmid uri:  " + cmidUri);
        return cmidUri;
    }

    private boolean okToDestroy() {
        String prop = CMID.DEBUG ? SystemProperties.get("cmid.skipwipe") : null;
        boolean skipWipe = (prop == null || prop.length() == 0) ? false : Integer.valueOf(prop) > 0;
        return !skipWipe;
    }

    public void destroyDevice(Context context) {
        final PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        final PowerManager.WakeLock wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        wakeLock.acquire(1000 * 60);
        final DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                setWipeStartedCommand(new Listener<Integer>() {
                    @Override
                    public void onResponse(Integer integer) {
                        if (CMID.DEBUG) Log.d(TAG, "setWipeStartedCommand onResponse="+integer);
                    }
                },
                new ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError volleyError) {
                        if (CMID.DEBUG) Log.d(TAG, "setWipeStartedCommand onErrorResponse:");
                        volleyError.printStackTrace();
                    }
                });
                if (okToDestroy()) {
                    if (CMID.DEBUG) Log.d(TAG, "Wipe enabled, wiping....");
                    dpm.wipeData(DevicePolicyManager.WIPE_EXTERNAL_STORAGE);
                } else {
                    if (CMID.DEBUG) Log.d(TAG, "Skipping wipe");
                }
            }
        });
        t.start();
    }

    public SharedPreferences getAuthPreferences() {
        return mContext.getSharedPreferences(CMID.AUTH_PREFERENCES,
                Context.MODE_PRIVATE);
    }

    public boolean isTokenExpired(AccountManager am, Account account) {
        final String expires_in = am.getUserData(account, CMID.AUTHTOKEN_EXPIRES_IN);
        long expiresTime = expires_in == null ? 0 : Long.valueOf(expires_in);
        return System.currentTimeMillis() > expiresTime;
    }

    public void expireToken(AccountManager am, Account account) {
        final String token = am.getUserData(account, CMID.AUTHTOKEN_TYPE_ACCESS);
        if (!TextUtils.isEmpty(token)) {
            am.invalidateAuthToken(CMID.ACCOUNT_TYPE_CMID, token);
        }
    }

    public HandshakeTokenItem getHandshakeToken(String secretToken, String command) {
        if (secretToken == null || command == null) {
            return null;
        }
        Cursor c = null;
        try {
            c = mContext.getContentResolver().query(CMIDProvider.CONTENT_URI, null,
                    CMIDProvider.HandshakeStoreColumns.SECRET + " = ? AND "
                    + CMIDProvider.HandshakeStoreColumns.METHOD + " = ?", new String[]{secretToken, command}, null);
            if (c != null && c.getCount() > 0) {
                c.moveToFirst();
                long id = c.getLong(c.getColumnIndex(CMIDProvider.HandshakeStoreColumns._ID));
                String token = c.getString(c.getColumnIndex(CMIDProvider.HandshakeStoreColumns.SECRET));
                long expiration = c.getLong(c.getColumnIndex(CMIDProvider.HandshakeStoreColumns.EXPIRATION));
                String method = c.getString(c.getColumnIndex(CMIDProvider.HandshakeStoreColumns.METHOD));
                return new HandshakeTokenItem(id, token, method, expiration);
            }
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return null;
    }

    public void cleanupHandshakeTokenByType(String command) {
        mContext.getContentResolver().delete(CMIDProvider.CONTENT_URI, CMIDProvider.HandshakeStoreColumns.METHOD + " = ?", new String[]{command});
    }

    public void generateHandshakeToken(AccountManager am, Account account, String uuid, String method) {
        ContentValues values = new ContentValues();
        String myToken = am.getPassword(account);
        String secret = CMIDUtils.digest("SHA512", (myToken+uuid));
        if (CMID.DEBUG) {
            Log.d(TAG, "Caching handshake token: " + secret);
        }
        values.put(CMIDProvider.HandshakeStoreColumns.SECRET, secret);
        values.put(CMIDProvider.HandshakeStoreColumns.METHOD, method);
        mContext.getContentResolver().insert(CMIDProvider.CONTENT_URI, values);
    }

    public static class HandshakeTokenItem {
        private long mId;
        private String mSecret;
        private long mExpiration;
        private String mMethod;

        public HandshakeTokenItem(long id, String secret, String method, long expiration) {
            mMethod = method;
            mId = id;
            mSecret = secret;
            mExpiration = expiration;
        }

        public long getId() {
            return mId;
        }

        public String getSecret() {
            return mSecret;
        }

        public long getExpiration() {
            return mExpiration;
        }

        public String getMethod() {
            return mMethod;
        }
    }

    private static interface TokenCallback {
        void onTokenReceived(String token);
        void onError(VolleyError error);
    }

}
