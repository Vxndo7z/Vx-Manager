package vxndo.manager.content.internal;

import android.annotation.*;
import android.content.*;
import android.content.pm.*;
import android.content.res.*;
import android.database.*;
import android.net.*;
import android.os.*;
import android.provider.*;
import android.text.*;
import android.webkit.*;
import java.io.*;
import java.util.*;
import org.xmlpull.v1.*;

import static org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
import static org.xmlpull.v1.XmlPullParser.START_TAG;

public class FileProviderInternal
extends ContentProvider {

	private static final String[] COLUMNS = {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE };
	private static final String META_DATA_FILE_PROVIDER_PATHS = "android.support.FILE_PROVIDER_PATHS";
	private static final String TAG_ROOT_PATH = "root-path";
	private static final String TAG_FILES_PATH = "files-path";
	private static final String TAG_CACHE_PATH = "cache-path";
	private static final String TAG_EXTERNAL = "external-path";
	private static final String TAG_EXTERNAL_FILES = "external-files-path";
	private static final String TAG_EXTERNAL_CACHE = "external-cache-path";
	private static final String TAG_EXTERNAL_MEDIA = "external-media-path";
	private static final String ATTR_NAME = "name";
	private static final String ATTR_PATH = "path";
	private static final String DISPLAYNAME_FIELD = "displayName";
	private static final File DEVICE_ROOT = new File("/");
	private static final HashMap<String, PathStrategy> sCache = new HashMap<>();

	private PathStrategy mStrategy;
	private int mResourceId;

	public FileProviderInternal() {
		mResourceId = 0;
	}

	protected FileProviderInternal(int resourceId) {
		mResourceId = resourceId;
	}

	@Override
	public boolean onCreate() {
		return true;
	}

	@SuppressWarnings("StringSplitter")
	@Override
	public void attachInfo(@NonNull Context context, @NonNull ProviderInfo info) {
		super.attachInfo(context, info);
		if (info.exported) {
			throw new SecurityException("Provider must not be exported");
		}
		if (!info.grantUriPermissions) {
			throw new SecurityException("Provider must grant uri permissions");
		}
		String authority = info.authority.split(";")[0];
		synchronized (sCache) {
			sCache.remove(authority);
		}
		mStrategy = getPathStrategy(context, authority, mResourceId);
	}

	public static Uri getUriForFile(@NonNull Context context, @NonNull String authority, @NonNull File file) {
		final PathStrategy strategy = getPathStrategy(context, authority, 0);
		return strategy.getUriForFile(file);
	}

	@SuppressLint("StreamFiles")
	@NonNull
	public static Uri getUriForFile(@NonNull Context context, @NonNull String authority, @NonNull File file, @NonNull String displayName) {
		Uri uri = getUriForFile(context, authority, file);
		return uri.buildUpon().appendQueryParameter(DISPLAYNAME_FIELD, displayName).build();
	}

	@NonNull
	@Override
	public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection, @Nullable String[] selectionArgs, @Nullable String sortOrder) {
		final File file = mStrategy.getFileForUri(uri);
		String displayName = uri.getQueryParameter(DISPLAYNAME_FIELD);
		if (projection == null) {
			projection = COLUMNS;
		}
		String[] cols = new String[projection.length];
		Object[] values = new Object[projection.length];
		int i = 0;
		for (String col : projection) {
			if (OpenableColumns.DISPLAY_NAME.equals(col)) {
				cols[i] = OpenableColumns.DISPLAY_NAME;
				values[i++] = (displayName == null) ? file.getName() : displayName;
			} else if (OpenableColumns.SIZE.equals(col)) {
				cols[i] = OpenableColumns.SIZE;
				values[i++] = file.length();
			}
		}
		cols = copyOf(cols, i);
		values = copyOf(values, i);
		final MatrixCursor cursor = new MatrixCursor(cols, 1);
		cursor.addRow(values);
		return cursor;
	}

	@Nullable
	@Override
	public String getType(@NonNull Uri uri) {
		final File file = mStrategy.getFileForUri(uri);
		final int lastDot = file.getName().lastIndexOf('.');
		if (lastDot >= 0) {
			final String extension = file.getName().substring(lastDot + 1);
			final String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
			if (mime != null) {
				return mime;
			}
		}
		return "application/octet-stream";
	}

	@Override
	public Uri insert(@NonNull Uri uri, @NonNull ContentValues values) {
		throw new UnsupportedOperationException("No external inserts");
	}

	@Override
	public int update(@NonNull Uri uri, @NonNull ContentValues values, @Nullable String selection, @Nullable String[] selectionArgs) {
		throw new UnsupportedOperationException("No external updates");
	}

	@Override
	public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
		final File file = mStrategy.getFileForUri(uri);
		return file.delete() ? 1 : 0;
	}

	@SuppressLint("UnknownNullness") // b/171012356
	@Override
	public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode) throws FileNotFoundException {
		final File file = mStrategy.getFileForUri(uri);
		final int fileMode = modeToMode(mode);
		return ParcelFileDescriptor.open(file, fileMode);
	}

	private static PathStrategy getPathStrategy(Context context, String authority, int resourceId) {
		PathStrategy strat;
		synchronized (sCache) {
			strat = sCache.get(authority);
			if (strat == null) {
				try {
					strat = parsePathStrategy(context, authority, resourceId);
				} catch (IOException e) {
					throw new IllegalArgumentException(
						"Failed to parse " + META_DATA_FILE_PROVIDER_PATHS + " meta-data", e);
				} catch (XmlPullParserException e) {
					throw new IllegalArgumentException(
						"Failed to parse " + META_DATA_FILE_PROVIDER_PATHS + " meta-data", e);
				}
				sCache.put(authority, strat);
			}
		}
		return strat;
	}

	static XmlResourceParser getFileProviderPathsMetaData(Context context, String authority, @Nullable ProviderInfo info, int resourceId) {
		if (info == null) {
			throw new IllegalArgumentException(
				"Couldn't find meta-data for provider with authority " + authority);
		}
		if (info.metaData == null && resourceId != 0) {
			info.metaData = new Bundle(1);
			info.metaData.putInt(META_DATA_FILE_PROVIDER_PATHS, resourceId);
		}
		final XmlResourceParser in = info.loadXmlMetaData(
			context.getPackageManager(), META_DATA_FILE_PROVIDER_PATHS);
		if (in == null) {
			throw new IllegalArgumentException(
				"Missing " + META_DATA_FILE_PROVIDER_PATHS + " meta-data");
		}
		return in;
	}

	private static PathStrategy parsePathStrategy(Context context, String authority, int resourceId)
	throws IOException, XmlPullParserException {
		final SimplePathStrategy strat = new SimplePathStrategy(authority);
		final ProviderInfo info = context.getPackageManager().resolveContentProvider(authority, PackageManager.GET_META_DATA);
		final XmlResourceParser in = getFileProviderPathsMetaData(context, authority, info, resourceId);
		int type;
		while ((type = in.next()) != END_DOCUMENT) {
			if (type == START_TAG) {
				final String tag = in.getName();
				final String name = in.getAttributeValue(null, ATTR_NAME);
				String path = in.getAttributeValue(null, ATTR_PATH);
				File target = null;
				if (TAG_ROOT_PATH.equals(tag)) {
					target = DEVICE_ROOT;
				} else if (TAG_FILES_PATH.equals(tag)) {
					target = context.getFilesDir();
				} else if (TAG_CACHE_PATH.equals(tag)) {
					target = context.getCacheDir();
				} else if (TAG_EXTERNAL.equals(tag)) {
					target = Environment.getExternalStorageDirectory();
				} else if (TAG_EXTERNAL_FILES.equals(tag)) {
					File[] externalFilesDirs = context.getExternalFilesDirs(null);
					if (externalFilesDirs.length > 0) {
						target = externalFilesDirs[0];
					}
				} else if (TAG_EXTERNAL_CACHE.equals(tag)) {
					File[] externalCacheDirs = context.getExternalCacheDirs();
					if (externalCacheDirs.length > 0) {
						target = externalCacheDirs[0];
					}
				} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
						   && TAG_EXTERNAL_MEDIA.equals(tag)) {
					File[] externalMediaDirs = Api21Impl.getExternalMediaDirs(context);
					if (externalMediaDirs.length > 0) {
						target = externalMediaDirs[0];
					}
				}
				if (target != null) {
					strat.addRoot(name, buildPath(target, path));
				}
			}
		}
		return strat;
	}

	interface PathStrategy {
		Uri getUriForFile(File file);
		File getFileForUri(Uri uri);
	}

	static class SimplePathStrategy
	implements PathStrategy {

		private final String mAuthority;
		private final HashMap<String, File> mRoots = new HashMap<>();
		SimplePathStrategy(String authority) {
			mAuthority = authority;
		}

		void addRoot(String name, File root) {
			if (TextUtils.isEmpty(name)) {
				throw new IllegalArgumentException("Name must not be empty");
			}
			try {
				root = root.getCanonicalFile();
			} catch (IOException e) {
				throw new IllegalArgumentException(
					"Failed to resolve canonical path for " + root, e);
			}
			mRoots.put(name, root);
		}

		@Override
		public Uri getUriForFile(File file) {
			String path;
			try {
				path = file.getCanonicalPath();
			} catch (IOException e) {
				throw new IllegalArgumentException("Failed to resolve canonical path for " + file);
			}
			Map.Entry<String, File> mostSpecific = null;
			for (Map.Entry<String, File> root : mRoots.entrySet()) {
				final String rootPath = root.getValue().getPath();
				if (path.startsWith(rootPath) && (mostSpecific == null
					|| rootPath.length() > mostSpecific.getValue().getPath().length())) {
					mostSpecific = root;
				}
			}
			if (mostSpecific == null) {
				throw new IllegalArgumentException(
					"Failed to find configured root that contains " + path);
			}
			final String rootPath = mostSpecific.getValue().getPath();
			if (rootPath.endsWith("/")) {
				path = path.substring(rootPath.length());
			} else {
				path = path.substring(rootPath.length() + 1);
			}
			path = Uri.encode(mostSpecific.getKey()) + '/' + Uri.encode(path, "/");
			return new Uri.Builder().scheme("content").authority(mAuthority).encodedPath(path).build();
		}

		@Override
		public File getFileForUri(Uri uri) {
			String path = uri.getEncodedPath();
			final int splitIndex = path.indexOf('/', 1);
			final String tag = Uri.decode(path.substring(1, splitIndex));
			path = Uri.decode(path.substring(splitIndex + 1));
			final File root = mRoots.get(tag);
			if (root == null) {
				throw new IllegalArgumentException("Unable to find configured root for " + uri);
			}
			File file = new File(root, path);
			try {
				file = file.getCanonicalFile();
			} catch (IOException e) {
				throw new IllegalArgumentException("Failed to resolve canonical path for " + file);
			}
			if (!file.getPath().startsWith(root.getPath())) {
				throw new SecurityException("Resolved path jumped beyond configured root");
			}
			return file;
		}
	}

	private static int modeToMode(String mode) {
		int modeBits;
		if ("r".equals(mode)) {
			modeBits = ParcelFileDescriptor.MODE_READ_ONLY;
		} else if ("w".equals(mode) || "wt".equals(mode)) {
			modeBits = ParcelFileDescriptor.MODE_WRITE_ONLY
				| ParcelFileDescriptor.MODE_CREATE
				| ParcelFileDescriptor.MODE_TRUNCATE;
		} else if ("wa".equals(mode)) {
			modeBits = ParcelFileDescriptor.MODE_WRITE_ONLY
				| ParcelFileDescriptor.MODE_CREATE
				| ParcelFileDescriptor.MODE_APPEND;
		} else if ("rw".equals(mode)) {
			modeBits = ParcelFileDescriptor.MODE_READ_WRITE
				| ParcelFileDescriptor.MODE_CREATE;
		} else if ("rwt".equals(mode)) {
			modeBits = ParcelFileDescriptor.MODE_READ_WRITE
				| ParcelFileDescriptor.MODE_CREATE
				| ParcelFileDescriptor.MODE_TRUNCATE;
		} else {
			throw new IllegalArgumentException("Invalid mode: " + mode);
		}
		return modeBits;
	}

	private static File buildPath(File base, String... segments) {
		File cur = base;
		for (String segment : segments) {
			if (segment != null) {
				cur = new File(cur, segment);
			}
		}
		return cur;
	}

	private static String[] copyOf(String[] original, int newLength) {
		final String[] result = new String[newLength];
		System.arraycopy(original, 0, result, 0, newLength);
		return result;
	}

	private static Object[] copyOf(Object[] original, int newLength) {
		final Object[] result = new Object[newLength];
		System.arraycopy(original, 0, result, 0, newLength);
		return result;
	}

	static class Api21Impl {

		private Api21Impl() {
		}

		static File[] getExternalMediaDirs(Context context) {
			return context.getExternalMediaDirs();
		}
	}
}
