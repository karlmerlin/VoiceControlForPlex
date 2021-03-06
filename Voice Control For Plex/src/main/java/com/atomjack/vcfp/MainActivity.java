package com.atomjack.vcfp;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.ArrayList;

import us.nineworlds.serenity.GDMReceiver;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.support.v4.content.LocalBroadcastManager;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.CheckBox;
import android.widget.ListAdapter;
import android.widget.ListView;

import com.atomjack.vcfp.model.MainSetting;
import com.atomjack.vcfp.model.MediaContainer;
import com.atomjack.vcfp.model.PlexClient;
import com.atomjack.vcfp.model.PlexDevice;
import com.atomjack.vcfp.model.PlexServer;
import com.atomjack.vcfp.net.PlexHttpClient;
import com.atomjack.vcfp.net.PlexHttpMediaContainerHandler;
import com.cubeactive.martin.inscription.WhatsNewDialog;
import com.google.gson.Gson;
import com.bugsense.trace.BugSenseHandler;

public class MainActivity extends Activity implements TextToSpeech.OnInitListener {
	public final static String PREFS = "VoiceControlForPlexPrefs";

	public final static int FEEDBACK_VOICE = 0;
	public final static int FEEDBACK_TOAST = 1;

	public final static String BUGSENSE_APIKEY = "879458d0";

	private final static int VOICE_FEEDBACK_SELECTED = 0;
	private final static int TASKER_PROJECT_IMPORTED = 1;

	private BroadcastReceiver gdmReceiver = new GDMReceiver();

	private ArrayList<String> availableVoices;
	private boolean settingErrorFeedback = false;

	private Feedback feedback;

	private Dialog searchDialog = null;

	private PlexServer server = null;
	private PlexClient client = null;

	private SharedPreferences mPrefs;
	private SharedPreferences.Editor mPrefsEditor;

	AlertDialog.Builder helpDialog;

	private LocalScan localScan;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		BugSenseHandler.initAndStartSession(MainActivity.this, BUGSENSE_APIKEY);


		final WhatsNewDialog whatsNewDialog = new WhatsNewDialog(this);
		whatsNewDialog.show();
		
		mPrefs = getSharedPreferences(PREFS, MODE_PRIVATE);
		mPrefsEditor = mPrefs.edit();
		Gson gson = new Gson();

		feedback = new Feedback(mPrefs, this);

		setContentView(R.layout.main);

		server = gson.fromJson(mPrefs.getString("Server", ""), PlexServer.class);
		client = gson.fromJson(mPrefs.getString("Client", ""), PlexClient.class);

		localScan = new LocalScan(this, MainActivity.class, mPrefs, new LocalScanHandler() {
			@Override
			public void onDeviceSelected(PlexDevice device) {
				if(device instanceof PlexServer)
					setServer((PlexServer)device);
				else if(device instanceof PlexClient)
					setClient((PlexClient)device);
			}
		});

		initMainWithServer();
	}

	public void installTasker(MenuItem item) {
		openAppInPlayStore("net.dinglisch.android.taskerm");
	}

	public void installUtter(MenuItem item) {
		openAppInPlayStore("com.brandall.nutter");
	}

	public void installAutoVoice(MenuItem item) {
		openAppInPlayStore("com.joaomgcd.autovoice");
	}

	public void showChangelog(MenuItem item) {
		final WhatsNewDialog whatsNewDialog = new WhatsNewDialog(this);
		whatsNewDialog.forceShow();
	}

	private void openAppInPlayStore(String packageName) {
		try {
			startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + packageName)));
		} catch (android.content.ActivityNotFoundException anfe) {
			startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("http://play.google.com/store/apps/details?id=" + packageName)));
		}
	}

	private boolean hasValidGoogleSearch() {
		try
		{
			PackageInfo pinfo = getPackageManager().getPackageInfo("com.google.android.googlequicksearchbox", 0);
			if(VoiceControlForPlexApplication.isVersionLessThan(pinfo.versionName, "3.4"))
				return true;
		} catch(Exception e) {}
		return false;
	}

	private boolean hasValidTasker() {
		PackageInfo pinfo;
		try
		{
			pinfo = getPackageManager().getPackageInfo("net.dinglisch.android.tasker", 0);
			return true;
		} catch(Exception e) {}
		try
		{
			pinfo = getPackageManager().getPackageInfo("net.dinglisch.android.taskerm", 0);
			return true;
		} catch(Exception e) {
			Logger.d("Exception getting google search version: " + e.getStackTrace());
		}
		return false;
	}

	private boolean hasValidAutoVoice() {
		try
		{
			if(hasValidTasker()) {
				PackageInfo pinfo = getPackageManager().getPackageInfo("com.joaomgcd.autovoice", 0);
				return true;
			}
		} catch(Exception e) {
			Logger.d("Exception getting autovoice version: " + e.getStackTrace());
		}
		return false;
	}

	private boolean hasValidUtter() {
		try
		{
			if(hasValidTasker()) {
				PackageInfo pinfo = getPackageManager().getPackageInfo("com.brandall.nutter", 0);
				return true;
			}
		} catch(Exception e) {
			Logger.d("Exception getting utter version: " + e.getStackTrace());
		}
		return false;
	}

	public void resumeChecked(View v) {
		mPrefsEditor.putBoolean("resume", ((CheckBox)v).isChecked());
		mPrefsEditor.commit();
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (requestCode == VOICE_FEEDBACK_SELECTED) {
			if (resultCode == TextToSpeech.Engine.CHECK_VOICE_DATA_PASS) {
				// success, create the TTS instance
				availableVoices = data.getStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES);
				TextToSpeech tts = new TextToSpeech(this, this);
//				errorsTts = new TextToSpeech(this, this);
			} else {
				// missing data, install it
				Intent installIntent = new Intent();
				installIntent.setAction(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA);
				startActivity(installIntent);
			}
		} else if(requestCode == TASKER_PROJECT_IMPORTED) {
			AlertDialog.Builder usageDialog = new AlertDialog.Builder(MainActivity.this);
			usageDialog.setTitle(R.string.import_tasker_project);
			usageDialog.setMessage(R.string.import_tasker_instructions);
			usageDialog.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int id) {
					dialog.dismiss();
					Intent i;
					PackageManager manager = getPackageManager();
					try {
						i = manager.getLaunchIntentForPackage("net.dinglisch.android.tasker");
						if (i == null)
							throw new PackageManager.NameNotFoundException();
						i.addCategory(Intent.CATEGORY_LAUNCHER);
						startActivity(i);
					} catch (PackageManager.NameNotFoundException e) {

					}
				}
			});
			usageDialog.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int id) {
					dialog.dismiss();
				}
			});
			usageDialog.show();
		}
	}

	private void initMainWithServer() {
		MainSetting setting_data[] = new MainSetting[] {
			new MainSetting(MainListAdapter.SettingHolder.TAG_SERVER, getResources().getString(R.string.stream_video_from_server), server != null ? server.name : getResources().getString(R.string.scan_all)),
			new MainSetting(MainListAdapter.SettingHolder.TAG_CLIENT, getResources().getString(R.string.to_the_client), client != null ? client.name : getResources().getString(R.string.not_set)),
			new MainSetting(MainListAdapter.SettingHolder.TAG_FEEDBACK, getResources().getString(R.string.feedback), mPrefs.getInt("feedback", FEEDBACK_TOAST) == FEEDBACK_VOICE ? getResources().getString(R.string.voice) : getResources().getString(R.string.toast)),
			new MainSetting(MainListAdapter.SettingHolder.TAG_ERRORS, getResources().getString(R.string.errors), mPrefs.getInt("errors", FEEDBACK_TOAST) == FEEDBACK_VOICE ? getResources().getString(R.string.voice) : getResources().getString(R.string.toast))
		};
		
		MainListAdapter adapter = new MainListAdapter(this, R.layout.main_setting_item_row, setting_data);
		
		ListView settingsList = (ListView)findViewById(R.id.settingsList);
		settingsList.setFooterDividersEnabled(true);
		settingsList.addFooterView(new View(settingsList.getContext()));
		settingsList.setAdapter(adapter);
		setListViewHeightBasedOnChildren(settingsList);
		settingsList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
			@Override
			public void onItemClick(AdapterView<?> adapter, View view, int position,
															long arg3) {
				MainListAdapter.SettingHolder holder = (MainListAdapter.SettingHolder) view.getTag();
				Logger.d("Clicked %s", holder.tag);
				if (holder.tag.equals(holder.TAG_SERVER)) {
					localScan.searchForPlexServers();
				} else if (holder.tag.equals(holder.TAG_CLIENT)) {
					localScan.getClients();
				} else if (holder.tag.equals(holder.TAG_FEEDBACK)) {
					selectFeedback();
				} else if (holder.tag.equals(holder.TAG_ERRORS)) {
					selectFeedback(true);
				}
			}
		});
		
		CheckBox resumeCheckbox = (CheckBox)findViewById(R.id.resumeCheckbox);
		resumeCheckbox.setChecked(mPrefs.getBoolean("resume", false));
	}
	
	public void settingRowHelpButtonClicked(View v) {
		String helpButtonClicked = v.getTag().toString();
		if(helpDialog == null) {
			helpDialog = new AlertDialog.Builder(MainActivity.this);
		}
		helpDialog.setTitle(R.string.app_name);

		if(helpButtonClicked.equals("server")) {
			helpDialog.setMessage(R.string.help_server);
		} else if(helpButtonClicked.equals("client")) {
			helpDialog.setMessage(R.string.help_client);
		} else if(helpButtonClicked.equals("feedback")) {
			helpDialog.setMessage(R.string.help_feedback);
		} else if(helpButtonClicked.equals("errors")) {
			helpDialog.setMessage(R.string.help_errors);
		}
		helpDialog.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int id) {
				dialog.dismiss();
			}
		});
		helpDialog.show();
	}

	private void selectFeedback() {
		selectFeedback(false);
	}

	private void selectFeedback(final boolean errors) {
		AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
		builder.setTitle(errors ? R.string.errors : R.string.feedback);
		final MainActivity ctx = this;
		builder.setPositiveButton(R.string.feedback_voice, new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int id) {
				mPrefsEditor.putInt(errors ? "errors" : "feedback", FEEDBACK_VOICE);
				mPrefsEditor.commit();
				Intent checkIntent = new Intent();
				checkIntent.setAction(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA);
				final TextToSpeech tts = new TextToSpeech(ctx, new TextToSpeech.OnInitListener() {
					@Override
					public void onInit(int i) {

					}
				});
				String engine = tts.getDefaultEngine();
				if (engine != null)
					checkIntent.setPackage(engine);
				settingErrorFeedback = errors;
				startActivityForResult(checkIntent, VOICE_FEEDBACK_SELECTED);
				initMainWithServer();
			}
		}).setNegativeButton(R.string.feedback_toast, new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int id) {
				mPrefsEditor.putInt(errors ? "errors" : "feedback", FEEDBACK_TOAST);
				mPrefsEditor.commit();
				initMainWithServer();
			}
		});
		AlertDialog d = builder.create();
		d.show();
	}

	public void installShortcut(MenuItem item) {
		Intent.ShortcutIconResource icon = Intent.ShortcutIconResource.fromContext(this, R.drawable.ic_launcher);

		Intent launchIntent = new Intent(this, ShortcutActivity.class);

		final Intent intent = new Intent();
		intent.putExtra(Intent.EXTRA_SHORTCUT_INTENT, launchIntent);
		intent.putExtra(Intent.EXTRA_SHORTCUT_NAME, getString(R.string.app_name));
		intent.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE, Intent.ShortcutIconResource.fromContext(this, R.drawable.ic_launcher));
		intent.setAction("com.android.launcher.action.INSTALL_SHORTCUT");

		sendBroadcast(intent);
	}

	public void showAbout(MenuItem item) {
		AlertDialog.Builder alertDialog = new AlertDialog.Builder(MainActivity.this)
						.setTitle(R.string.app_name)
						.setMessage(R.string.about_text);

		alertDialog.show();
	}

	public void donate(MenuItem item) {
		Intent intent = new Intent(Intent.ACTION_VIEW,
						Uri.parse("https://www.paypal.com/cgi-bin/webscr?cmd=_s-xclick&hosted_button_id=UJF9QY9QELERG"));
		startActivity(intent);
	}

	public void importTaskerProject(MenuItem item) {
		String xmlfile = "VoiceControlForPlex.prj.xml";

		try
		{
			AssetManager am = getAssets();
			InputStream is = am.open(xmlfile);
			int size = is.available();
			byte[] buffer = new byte[size];
			is.read(buffer);
			is.close();

			String xmlContents = new String(buffer);
			xmlContents = xmlContents.replace("%RECOGNITION_REGEX%", getString(R.string.pattern_recognition));
			buffer = xmlContents.getBytes();
			Logger.d("directory: %s", Environment.getExternalStorageDirectory());

			File f = new File(Environment.getExternalStorageDirectory() + "/" + xmlfile);
			FileOutputStream fos = new FileOutputStream(f);
			fos.write(buffer);
			fos.close();

			Logger.d("Wrote xml file");

			Intent i = new Intent();
			i.setAction(Intent.ACTION_VIEW);
			i.setDataAndType(Uri.fromFile(f), "text/xml");
			startActivityForResult(i, TASKER_PROJECT_IMPORTED);
		} catch (Exception e) {
			Logger.d("Exception opening tasker profile xml: ");
			e.printStackTrace();
			return;
		}


	}
	
	public void showUsageExamples(View v) {
		AlertDialog.Builder usageDialog = new AlertDialog.Builder(MainActivity.this);
		usageDialog.setTitle(R.string.help_usage_button);
		usageDialog.setMessage(R.string.help_usage);
		usageDialog.setPositiveButton(R.string.got_it, new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int id) {
						dialog.dismiss();
					}
		});
		usageDialog.show();
	}

	/**** Method for Setting the Height of the ListView dynamically.
	 **** Hack to fix the issue of not showing all the items of the ListView
	 **** when placed inside a ScrollView  ****/
	public static void setListViewHeightBasedOnChildren(ListView listView) {
		ListAdapter listAdapter = listView.getAdapter();
		if (listAdapter == null)
			return;

		int desiredWidth = View.MeasureSpec.makeMeasureSpec(listView.getWidth(), View.MeasureSpec.UNSPECIFIED);
		int totalHeight = 0;
		View view = null;
		for (int i = 0; i < listAdapter.getCount(); i++) {
			view = listAdapter.getView(i, view, listView);
			if (i == 0)
				view.setLayoutParams(new ViewGroup.LayoutParams(desiredWidth, ViewGroup.LayoutParams.WRAP_CONTENT));

			view.measure(desiredWidth, View.MeasureSpec.UNSPECIFIED);
			totalHeight += view.getMeasuredHeight();
		}
		ViewGroup.LayoutParams params = listView.getLayoutParams();
		params.height = totalHeight + (listView.getDividerHeight() * (listAdapter.getCount() - 1));
		listView.setLayoutParams(params);
		listView.requestLayout();
	}

	@Override
	public void onNewIntent(Intent intent) {
		Logger.d("ON NEW INTENT IN MAINACTIVITY");
		String from = intent.getStringExtra("FROM");
		Logger.d("From: %s", from);
		if(from == null) {
		} else if(from.equals("GDMReceiver")) {
			Logger.d("Origin: " + intent.getStringExtra("ORIGIN"));
			String origin = intent.getStringExtra("ORIGIN") == null ? "" : intent.getStringExtra("ORIGIN");
			if(origin.equals("MainActivity")) {
				Logger.d("Got " + VoiceControlForPlexApplication.getPlexMediaServers().size() + " servers");
				if(VoiceControlForPlexApplication.getPlexMediaServers().size() > 0) {
					localScan.showPlexServers();
				} else {
					if(searchDialog != null)
						searchDialog.hide();
					AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
						builder.setTitle("No Plex Servers Found");
						builder.setCancelable(false)
							.setNeutralButton(R.string.ok, new DialogInterface.OnClickListener() {
										public void onClick(DialogInterface dialog, int id) {
												dialog.cancel();
										}
							});
						AlertDialog d = builder.create();
						d.show();
				}
			} else if(origin.equals("ScanForClients")) {
				// No default server specified, so we need to search all servers for all clients
				localScan.scanServersForClients();
			}
		}
	}
	


	private void setServer(PlexServer _server) {
		Logger.d("Setting Server %s", _server.name);
		if(_server.name.equals(getResources().getString(R.string.scan_all)))
			server = null;
		else
			server = _server;
		saveSettings();

		if(client == null) {
			if(_server.name.equals(getResources().getString(R.string.scan_all))) {
				localScan.scanServersForClients();
			} else {
				PlexHttpClient.get(server.getBaseURL(), null, new PlexHttpMediaContainerHandler() {
					@Override
					public void onSuccess(MediaContainer mediaContainer) {
						Logger.d("Machine id: " + mediaContainer.machineIdentifier);
						localScan.getClients(mediaContainer);
					}

					@Override
					public void onFailure(Throwable error) {
						feedback.e(getResources().getString(R.string.got_error), error.getMessage());
						finish();
					}
				});
			}
		} else {
			initMainWithServer();
		}
		
	}
	
	private void setClient(PlexClient _client) {
		client = _client;
		Logger.d("Selected client: " + client.name);
		saveSettings();
		initMainWithServer();
	}
	
	private void saveSettings() {
		Gson gson = new Gson();
		mPrefsEditor.putString("Server", gson.toJson(server));
		mPrefsEditor.putString("Client", gson.toJson(client));
		mPrefsEditor.putBoolean("resume", mPrefs.getBoolean("resume", false));
		mPrefsEditor.commit();
	}
	
	@Override
	public boolean onCreateOptionsMenu(Menu _menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.menu_main, _menu);
		if (!hasValidAutoVoice() && !hasValidUtter()) {
			_menu.findItem(R.id.menu_tasker_import).setVisible(false);
			if (!hasValidTasker()) {
				_menu.findItem(R.id.menu_install_tasker).setVisible(true);
			}
			if (!hasValidUtter()) {
				_menu.findItem(R.id.menu_install_utter).setVisible(true);
			}
			if (!hasValidAutoVoice()) {
				_menu.findItem(R.id.menu_install_autovoice).setVisible(true);
			}
		}
		return true;
	}

	@Override
	protected void onDestroy() {
		if(gdmReceiver != null) {
			LocalBroadcastManager.getInstance(this).unregisterReceiver(gdmReceiver);
		}
		feedback.destroy();
		super.onDestroy();
	}

	@Override
	protected void onPause() {
		super.onPause();
		if(gdmReceiver != null) {
			LocalBroadcastManager.getInstance(this).unregisterReceiver(gdmReceiver);
		}
	}

	@Override
	protected void onResume() {
		super.onResume();
		if(gdmReceiver != null) {
			IntentFilter filters = new IntentFilter();
			filters.addAction(GDMService.MSG_RECEIVED);
			filters.addAction(GDMService.SOCKET_CLOSED);
			LocalBroadcastManager.getInstance(this).registerReceiver(gdmReceiver,
				filters);
		}
	}

	@Override
	public void finish() {
		if(getCallingActivity() != null)
		{
			Bundle bundle = new Bundle();
			// Pass the entire string of what was said into autovoice
			bundle.putString("com.atomjack.vcfp.intent.ARGUMENTS", "%avcomm");
			if (TaskerPlugin.Setting.hostSupportsOnFireVariableReplacement(this))
				TaskerPlugin.Setting.setVariableReplaceKeys(bundle, new String[]{"com.atomjack.vcfp.intent.ARGUMENTS"});

			Intent i = new Intent();
			i.putExtras(bundle);

			String blurb = "Server: " + (server != null ? server.name : getResources().getString(R.string.scan_all));
			blurb += " | Client: " + (client != null ? client.name : "Not specified.");
			if(mPrefs.getBoolean("resume", false))
				blurb += " (resume)";


			i.putExtra("com.twofortyfouram.locale.intent.extra.BLURB", blurb);
			setResult(Activity.RESULT_OK, i);
		}
		super.finish();
	}

	@Override
	public void onInit(int status) {
		if (status == TextToSpeech.SUCCESS) {
			final String pref = settingErrorFeedback ? VoiceControlForPlexApplication.PREF_ERRORS_VOICE : VoiceControlForPlexApplication.PREF_FEEDBACK_VOICE;
			if (availableVoices != null) {
				AlertDialog.Builder adb = new AlertDialog.Builder(this);
				final CharSequence items[] = availableVoices.toArray(new CharSequence[availableVoices.size()]);
				int selectedVoice = -1;
				String v = mPrefs.getString(pref, "Locale.US");
				if (availableVoices.indexOf(v) > -1)
					selectedVoice = availableVoices.indexOf(v);

				adb.setSingleChoiceItems(items, selectedVoice, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface d, int n) {
						mPrefsEditor.putString(pref, items[n].toString());
						mPrefsEditor.commit();
						d.dismiss();
					}
				});
				adb.setNegativeButton(R.string.cancel, null);
				adb.setTitle(R.string.select_voice);
				adb.show();
			} else {
				mPrefsEditor.putString(pref, "Locale.US");
				mPrefsEditor.commit();
			}
		}
	}
}


