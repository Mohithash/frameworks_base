/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.content;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.res.AssetFileDescriptor;
import android.database.BulkCursorDescriptor;
import android.database.BulkCursorToCursorAdaptor;
import android.database.CharArrayBuffer;
import android.database.Cursor;
import android.database.CursorToBulkCursorAdaptor;
import android.database.CursorWrapper;
import android.database.DatabaseUtils;
import android.database.IContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ICancellationSignal;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.Process;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.privacykit.IPrivacyKitManager;
import android.privacykit.PrivacyKitKeys;
import android.ravenwood.annotation.RavenwoodKeepWholeClass;
import android.util.Log;

import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * @hide
 */
@RavenwoodKeepWholeClass
abstract public class ContentProviderNative extends Binder implements IContentProvider {
    public ContentProviderNative()
    {
        attachInterface(this, descriptor);
    }

    /**
     * Cast a Binder object into a content resolver interface, generating
     * a proxy if needed.
     */
    @UnsupportedAppUsage
    static public IContentProvider asInterface(IBinder obj)
    {
        if (obj == null) {
            return null;
        }
        IContentProvider in =
            (IContentProvider)obj.queryLocalInterface(descriptor);
        if (in != null) {
            return in;
        }

        return new ContentProviderProxy(obj);
    }

    /**
     * Gets the name of the content provider.
     * Should probably be part of the {@link IContentProvider} interface.
     * @return The content provider name.
     */
    public abstract String getProviderName();

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        try {
            switch (code) {
                case QUERY_TRANSACTION:
                {
                    data.enforceInterface(IContentProvider.descriptor);

                    AttributionSource attributionSource = AttributionSource.CREATOR
                            .createFromParcel(data);
                    Uri url = Uri.CREATOR.createFromParcel(data);

                    // String[] projection
                    int num = data.readInt();
                    String[] projection = null;
                    if (num > 0) {
                        projection = new String[num];
                        for (int i = 0; i < num; i++) {
                            projection[i] = data.readString();
                        }
                    }

                    Bundle queryArgs = data.readBundle();
                    IContentObserver observer = IContentObserver.Stub.asInterface(
                            data.readStrongBinder());
                    ICancellationSignal cancellationSignal = ICancellationSignal.Stub.asInterface(
                            data.readStrongBinder());

                    Cursor cursor = query(attributionSource, url, projection, queryArgs,
                            cancellationSignal);
                    if (cursor != null) {
                        CursorToBulkCursorAdaptor adaptor = null;

                        try {
                            adaptor = new CursorToBulkCursorAdaptor(cursor, observer,
                                    getProviderName());
                            cursor = null;

                            BulkCursorDescriptor d = adaptor.getBulkCursorDescriptor();
                            adaptor = null;

                            reply.writeNoException();
                            reply.writeInt(1);
                            d.writeToParcel(reply, Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
                        } finally {
                            // Close cursor if an exception was thrown while constructing the adaptor.
                            if (adaptor != null) {
                                adaptor.close();
                            }
                            if (cursor != null) {
                                cursor.close();
                            }
                        }
                    } else {
                        reply.writeNoException();
                        reply.writeInt(0);
                    }

                    return true;
                }

                case GET_TYPE_TRANSACTION:
                {
                    data.enforceInterface(IContentProvider.descriptor);
                    AttributionSource attributionSource = AttributionSource.CREATOR
                            .createFromParcel(data);
                    Uri url = Uri.CREATOR.createFromParcel(data);
                    String type = getType(attributionSource, url);
                    reply.writeNoException();
                    reply.writeString(type);

                    return true;
                }

                case GET_TYPE_ASYNC_TRANSACTION: {
                    data.enforceInterface(IContentProvider.descriptor);
                    AttributionSource attributionSource = AttributionSource.CREATOR
                            .createFromParcel(data);
                    Uri url = Uri.CREATOR.createFromParcel(data);
                    RemoteCallback callback = RemoteCallback.CREATOR.createFromParcel(data);
                    getTypeAsync(attributionSource, url, callback);
                    return true;
                }

                case GET_TYPE_ANONYMOUS_ASYNC_TRANSACTION: {
                    data.enforceInterface(IContentProvider.descriptor);
                    Uri url = Uri.CREATOR.createFromParcel(data);
                    RemoteCallback callback = RemoteCallback.CREATOR.createFromParcel(data);
                    getTypeAnonymousAsync(url, callback);
                    return true;
                }

                case INSERT_TRANSACTION:
                {
                    data.enforceInterface(IContentProvider.descriptor);
                    AttributionSource attributionSource = AttributionSource.CREATOR
                            .createFromParcel(data);
                    Uri url = Uri.CREATOR.createFromParcel(data);
                    ContentValues values = ContentValues.CREATOR.createFromParcel(data);
                    Bundle extras = data.readBundle();

                    Uri out = insert(attributionSource, url, values, extras);
                    reply.writeNoException();
                    Uri.writeToParcel(reply, out);
                    return true;
                }

                case BULK_INSERT_TRANSACTION:
                {
                    data.enforceInterface(IContentProvider.descriptor);
                    AttributionSource attributionSource = AttributionSource.CREATOR
                            .createFromParcel(data);
                    Uri url = Uri.CREATOR.createFromParcel(data);
                    ContentValues[] values = data.createTypedArray(ContentValues.CREATOR);

                    int count = bulkInsert(attributionSource, url, values);
                    reply.writeNoException();
                    reply.writeInt(count);
                    return true;
                }

                case APPLY_BATCH_TRANSACTION:
                {
                    data.enforceInterface(IContentProvider.descriptor);
                    AttributionSource attributionSource = AttributionSource.CREATOR
                            .createFromParcel(data);
                    String authority = data.readString();
                    final int numOperations = data.readInt();
                    final ArrayList<ContentProviderOperation> operations =
                            new ArrayList<>(numOperations);
                    for (int i = 0; i < numOperations; i++) {
                        operations.add(i, ContentProviderOperation.CREATOR.createFromParcel(data));
                    }
                    final ContentProviderResult[] results = applyBatch(attributionSource,
                            authority, operations);
                    reply.writeNoException();
                    reply.writeTypedArray(results, 0);
                    return true;
                }

                case DELETE_TRANSACTION:
                {
                    data.enforceInterface(IContentProvider.descriptor);
                    AttributionSource attributionSource = AttributionSource.CREATOR
                            .createFromParcel(data);
                    Uri url = Uri.CREATOR.createFromParcel(data);
                    Bundle extras = data.readBundle();

                    int count = delete(attributionSource, url, extras);

                    reply.writeNoException();
                    reply.writeInt(count);
                    return true;
                }

                case UPDATE_TRANSACTION:
                {
                    data.enforceInterface(IContentProvider.descriptor);
                    AttributionSource attributionSource = AttributionSource.CREATOR
                            .createFromParcel(data);
                    Uri url = Uri.CREATOR.createFromParcel(data);
                    ContentValues values = ContentValues.CREATOR.createFromParcel(data);
                    Bundle extras = data.readBundle();

                    int count = update(attributionSource, url, values, extras);

                    reply.writeNoException();
                    reply.writeInt(count);
                    return true;
                }

                case OPEN_FILE_TRANSACTION:
                {
                    data.enforceInterface(IContentProvider.descriptor);
                    AttributionSource attributionSource = AttributionSource.CREATOR
                            .createFromParcel(data);
                    Uri url = Uri.CREATOR.createFromParcel(data);
                    String mode = data.readString();
                    ICancellationSignal signal = ICancellationSignal.Stub.asInterface(
                            data.readStrongBinder());

                    ParcelFileDescriptor fd;
                    fd = openFile(attributionSource, url, mode, signal);
                    reply.writeNoException();
                    if (fd != null) {
                        reply.writeInt(1);
                        fd.writeToParcel(reply,
                                Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
                    } else {
                        reply.writeInt(0);
                    }
                    return true;
                }

                case OPEN_ASSET_FILE_TRANSACTION:
                {
                    data.enforceInterface(IContentProvider.descriptor);
                    AttributionSource attributionSource = AttributionSource.CREATOR
                            .createFromParcel(data);
                    Uri url = Uri.CREATOR.createFromParcel(data);
                    String mode = data.readString();
                    ICancellationSignal signal = ICancellationSignal.Stub.asInterface(
                            data.readStrongBinder());

                    AssetFileDescriptor fd;
                    fd = openAssetFile(attributionSource, url, mode, signal);
                    reply.writeNoException();
                    if (fd != null) {
                        reply.writeInt(1);
                        fd.writeToParcel(reply,
                                Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
                    } else {
                        reply.writeInt(0);
                    }
                    return true;
                }

                case CALL_TRANSACTION:
                {
                    data.enforceInterface(IContentProvider.descriptor);

                    AttributionSource attributionSource = AttributionSource.CREATOR
                            .createFromParcel(data);
                    String authority = data.readString();
                    String method = data.readString();
                    String stringArg = data.readString();
                    Bundle extras = data.readBundle();

                    Bundle responseBundle = call(attributionSource, authority, method,
                            stringArg, extras);

                    reply.writeNoException();
                    reply.writeBundle(responseBundle);
                    return true;
                }

                case GET_STREAM_TYPES_TRANSACTION:
                {
                    data.enforceInterface(IContentProvider.descriptor);
                    AttributionSource attributionSource = AttributionSource.CREATOR
                            .createFromParcel(data);
                    Uri url = Uri.CREATOR.createFromParcel(data);
                    String mimeTypeFilter = data.readString();
                    String[] types = getStreamTypes(attributionSource, url, mimeTypeFilter);
                    reply.writeNoException();
                    reply.writeStringArray(types);

                    return true;
                }

                case OPEN_TYPED_ASSET_FILE_TRANSACTION:
                {
                    data.enforceInterface(IContentProvider.descriptor);
                    AttributionSource attributionSource = AttributionSource.CREATOR
                            .createFromParcel(data);
                    Uri url = Uri.CREATOR.createFromParcel(data);
                    String mimeType = data.readString();
                    Bundle opts = data.readBundle();
                    ICancellationSignal signal = ICancellationSignal.Stub.asInterface(
                            data.readStrongBinder());

                    AssetFileDescriptor fd;
                    fd = openTypedAssetFile(attributionSource, url, mimeType, opts, signal);
                    reply.writeNoException();
                    if (fd != null) {
                        reply.writeInt(1);
                        fd.writeToParcel(reply,
                                Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
                    } else {
                        reply.writeInt(0);
                    }
                    return true;
                }

                case CREATE_CANCELATION_SIGNAL_TRANSACTION:
                {
                    data.enforceInterface(IContentProvider.descriptor);

                    ICancellationSignal cancellationSignal = createCancellationSignal();
                    reply.writeNoException();
                    reply.writeStrongBinder(cancellationSignal.asBinder());
                    return true;
                }

                case CANONICALIZE_TRANSACTION:
                {
                    data.enforceInterface(IContentProvider.descriptor);
                    AttributionSource attributionSource = AttributionSource.CREATOR
                            .createFromParcel(data);
                    Uri url = Uri.CREATOR.createFromParcel(data);

                    Uri out = canonicalize(attributionSource, url);
                    reply.writeNoException();
                    Uri.writeToParcel(reply, out);
                    return true;
                }

                case CANONICALIZE_ASYNC_TRANSACTION: {
                    data.enforceInterface(IContentProvider.descriptor);
                    AttributionSource attributionSource = AttributionSource.CREATOR
                            .createFromParcel(data);
                    Uri uri = Uri.CREATOR.createFromParcel(data);
                    RemoteCallback callback = RemoteCallback.CREATOR.createFromParcel(data);
                    canonicalizeAsync(attributionSource, uri, callback);
                    return true;
                }

                case UNCANONICALIZE_TRANSACTION:
                {
                    data.enforceInterface(IContentProvider.descriptor);
                    AttributionSource attributionSource = AttributionSource.CREATOR
                            .createFromParcel(data);
                    Uri url = Uri.CREATOR.createFromParcel(data);

                    Uri out = uncanonicalize(attributionSource, url);
                    reply.writeNoException();
                    Uri.writeToParcel(reply, out);
                    return true;
                }

                case UNCANONICALIZE_ASYNC_TRANSACTION: {
                    data.enforceInterface(IContentProvider.descriptor);
                    AttributionSource attributionSource = AttributionSource.CREATOR
                            .createFromParcel(data);
                    Uri uri = Uri.CREATOR.createFromParcel(data);
                    RemoteCallback callback = RemoteCallback.CREATOR.createFromParcel(data);
                    uncanonicalizeAsync(attributionSource, uri, callback);
                    return true;
                }

                case REFRESH_TRANSACTION: {
                    data.enforceInterface(IContentProvider.descriptor);
                    AttributionSource attributionSource = AttributionSource.CREATOR
                            .createFromParcel(data);
                    Uri url = Uri.CREATOR.createFromParcel(data);
                    Bundle extras = data.readBundle();
                    ICancellationSignal signal = ICancellationSignal.Stub.asInterface(
                            data.readStrongBinder());

                    boolean out = refresh(attributionSource, url, extras, signal);
                    reply.writeNoException();
                    reply.writeInt(out ? 0 : -1);
                    return true;
                }

                case CHECK_URI_PERMISSION_TRANSACTION: {
                    data.enforceInterface(IContentProvider.descriptor);
                    AttributionSource attributionSource = AttributionSource.CREATOR
                            .createFromParcel(data);
                    Uri uri = Uri.CREATOR.createFromParcel(data);
                    int uid = data.readInt();
                    int modeFlags = data.readInt();

                    int out = checkUriPermission(attributionSource, uri, uid, modeFlags);
                    reply.writeNoException();
                    reply.writeInt(out);
                    return true;
                }
            }
        } catch (Exception e) {
            DatabaseUtils.writeExceptionToParcel(reply, e);
            return true;
        }

        return super.onTransact(code, data, reply, flags);
    }

    @Override
    public IBinder asBinder()
    {
        return this;
    }
}


final class ContentProviderProxy implements IContentProvider
{
    public ContentProviderProxy(IBinder remote)
    {
        mRemote = remote;
    }

    @Override
    public IBinder asBinder()
    {
        return mRemote;
    }

    @Override
    public Cursor query(@NonNull AttributionSource attributionSource, Uri url,
            @Nullable String[] projection, @Nullable Bundle queryArgs,
            @Nullable ICancellationSignal cancellationSignal)
            throws RemoteException {
        // PrivacyKit-Native: GSF ID (the gservices "android_id" row). The
        // discriminator deliberately runs before anything else and is a single
        // String#equals against an authority that ContentResolver has already
        // parsed and cached on this very Uri instance (acquireUnstableProvider
        // calls getAuthority() to find the provider in the first place), so a
        // non-gservices query - i.e. essentially every query on the device -
        // pays one length compare and nothing more. Only when the authority
        // matches do we touch PrivacyKit, and the value it hands back is then
        // memoised for the whole process.
        final String gsfId = (url != null
                && PrivacyKitGservicesFilter.isGservicesAuthority(url.getAuthority()))
                ? PrivacyKitGservicesFilter.getSpoofedId(attributionSource.getPackageName())
                : null;

        BulkCursorToCursorAdaptor adaptor = new BulkCursorToCursorAdaptor();
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IContentProvider.descriptor);

            attributionSource.writeToParcel(data, 0);
            url.writeToParcel(data, 0);
            int length = 0;
            if (projection != null) {
                length = projection.length;
            }
            data.writeInt(length);
            for (int i = 0; i < length; i++) {
                data.writeString(projection[i]);
            }
            data.writeBundle(queryArgs);
            data.writeStrongBinder(adaptor.getObserver().asBinder());
            data.writeStrongBinder(
                    cancellationSignal != null ? cancellationSignal.asBinder() : null);

            mRemote.transact(IContentProvider.QUERY_TRANSACTION, data, reply, 0);

            DatabaseUtils.readExceptionFromParcel(reply);

            if (reply.readInt() != 0) {
                BulkCursorDescriptor d = BulkCursorDescriptor.CREATOR.createFromParcel(reply);
                Binder.copyAllowBlocking(mRemote, (d.cursor != null) ? d.cursor.asBinder() : null);
                adaptor.initialize(d);
            } else {
                adaptor.close();
                adaptor = null;
            }
            if (gsfId != null) {
                // Fails open to the untouched cursor (including a null one).
                return PrivacyKitGservicesFilter.filterCursor(adaptor, gsfId);
            }
            return adaptor;
        } catch (RemoteException ex) {
            adaptor.close();
            throw ex;
        } catch (RuntimeException ex) {
            adaptor.close();
            throw ex;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    @Override
    public String getType(AttributionSource attributionSource, Uri url) throws RemoteException
    {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IContentProvider.descriptor);
            attributionSource.writeToParcel(data, 0);
            url.writeToParcel(data, 0);

            mRemote.transact(IContentProvider.GET_TYPE_TRANSACTION, data, reply, 0);

            DatabaseUtils.readExceptionFromParcel(reply);
            String out = reply.readString();
            return out;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    @Override
    /* oneway */ public void getTypeAsync(AttributionSource attributionSource,
            Uri uri, RemoteCallback callback) throws RemoteException {
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(IContentProvider.descriptor);
            attributionSource.writeToParcel(data, 0);
            uri.writeToParcel(data, 0);
            callback.writeToParcel(data, 0);

            mRemote.transact(IContentProvider.GET_TYPE_ASYNC_TRANSACTION, data, null,
                    IBinder.FLAG_ONEWAY);
        } finally {
            data.recycle();
        }
    }

    @Override
    /* oneway */ public void getTypeAnonymousAsync(Uri uri, RemoteCallback callback)
            throws RemoteException {
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(IContentProvider.descriptor);

            uri.writeToParcel(data, 0);
            callback.writeToParcel(data, 0);

            mRemote.transact(IContentProvider.GET_TYPE_ANONYMOUS_ASYNC_TRANSACTION, data, null,
                    IBinder.FLAG_ONEWAY);
        } finally {
            data.recycle();
        }
    }

    @Override
    public Uri insert(@NonNull AttributionSource attributionSource, Uri url,
            ContentValues values, Bundle extras) throws RemoteException
    {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IContentProvider.descriptor);

            attributionSource.writeToParcel(data, 0);
            url.writeToParcel(data, 0);
            values.writeToParcel(data, 0);
            data.writeBundle(extras);

            mRemote.transact(IContentProvider.INSERT_TRANSACTION, data, reply, 0);

            DatabaseUtils.readExceptionFromParcel(reply);
            Uri out = Uri.CREATOR.createFromParcel(reply);
            return out;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    @Override
    public int bulkInsert(@NonNull AttributionSource attributionSource, Uri url,
            ContentValues[] values) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IContentProvider.descriptor);

            attributionSource.writeToParcel(data, 0);
            url.writeToParcel(data, 0);
            data.writeTypedArray(values, 0);

            mRemote.transact(IContentProvider.BULK_INSERT_TRANSACTION, data, reply, 0);

            DatabaseUtils.readExceptionFromParcel(reply);
            int count = reply.readInt();
            return count;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    @Override
    public ContentProviderResult[] applyBatch(@NonNull AttributionSource attributionSource,
            String authority, ArrayList<ContentProviderOperation> operations)
            throws RemoteException, OperationApplicationException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IContentProvider.descriptor);
            attributionSource.writeToParcel(data, 0);
            data.writeString(authority);
            data.writeInt(operations.size());
            for (ContentProviderOperation operation : operations) {
                operation.writeToParcel(data, 0);
            }
            mRemote.transact(IContentProvider.APPLY_BATCH_TRANSACTION, data, reply, 0);

            DatabaseUtils.readExceptionWithOperationApplicationExceptionFromParcel(reply);
            final ContentProviderResult[] results =
                    reply.createTypedArray(ContentProviderResult.CREATOR);
            return results;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    @Override
    public int delete(@NonNull AttributionSource attributionSource, Uri url, Bundle extras)
            throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IContentProvider.descriptor);

            attributionSource.writeToParcel(data, 0);
            url.writeToParcel(data, 0);
            data.writeBundle(extras);

            mRemote.transact(IContentProvider.DELETE_TRANSACTION, data, reply, 0);

            DatabaseUtils.readExceptionFromParcel(reply);
            int count = reply.readInt();
            return count;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    @Override
    public int update(@NonNull AttributionSource attributionSource, Uri url,
            ContentValues values, Bundle extras) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IContentProvider.descriptor);

            attributionSource.writeToParcel(data, 0);
            url.writeToParcel(data, 0);
            values.writeToParcel(data, 0);
            data.writeBundle(extras);

            mRemote.transact(IContentProvider.UPDATE_TRANSACTION, data, reply, 0);

            DatabaseUtils.readExceptionFromParcel(reply);
            int count = reply.readInt();
            return count;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    @Override
    public ParcelFileDescriptor openFile(@NonNull AttributionSource attributionSource, Uri url,
            String mode, ICancellationSignal signal)
            throws RemoteException, FileNotFoundException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IContentProvider.descriptor);

            attributionSource.writeToParcel(data, 0);
            url.writeToParcel(data, 0);
            data.writeString(mode);
            data.writeStrongBinder(signal != null ? signal.asBinder() : null);

            mRemote.transact(IContentProvider.OPEN_FILE_TRANSACTION, data, reply, 0);

            DatabaseUtils.readExceptionWithFileNotFoundExceptionFromParcel(reply);
            int has = reply.readInt();
            ParcelFileDescriptor fd = has != 0 ? ParcelFileDescriptor.CREATOR
                    .createFromParcel(reply) : null;
            return fd;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    @Override
    public AssetFileDescriptor openAssetFile(@NonNull AttributionSource attributionSource,
            Uri url, String mode, ICancellationSignal signal)
            throws RemoteException, FileNotFoundException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IContentProvider.descriptor);

            attributionSource.writeToParcel(data, 0);
            url.writeToParcel(data, 0);
            data.writeString(mode);
            data.writeStrongBinder(signal != null ? signal.asBinder() : null);

            mRemote.transact(IContentProvider.OPEN_ASSET_FILE_TRANSACTION, data, reply, 0);

            DatabaseUtils.readExceptionWithFileNotFoundExceptionFromParcel(reply);
            int has = reply.readInt();
            AssetFileDescriptor fd = has != 0
                    ? AssetFileDescriptor.CREATOR.createFromParcel(reply) : null;
            return fd;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    @Override
    public Bundle call(@NonNull AttributionSource attributionSource, String authority,
            String method, String request, Bundle extras) throws RemoteException {
        // PrivacyKit-Native: the second route to the gservices "android_id".
        // Hooking query() alone would mean an app that reads the value both
        // ways gets two different answers, which is a louder fingerprint than
        // the real id. Here the authority is already a plain String, so the
        // discriminator costs one String#equals.
        final String gsfId = PrivacyKitGservicesFilter.isGservicesAuthority(authority)
                ? PrivacyKitGservicesFilter.getSpoofedId(attributionSource.getPackageName())
                : null;

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IContentProvider.descriptor);

            attributionSource.writeToParcel(data, 0);
            data.writeString(authority);
            data.writeString(method);
            data.writeString(request);
            data.writeBundle(extras);

            mRemote.transact(IContentProvider.CALL_TRANSACTION, data, reply, 0);

            DatabaseUtils.readExceptionFromParcel(reply);
            Bundle bundle = reply.readBundle();
            if (gsfId != null) {
                PrivacyKitGservicesFilter.filterCallResult(bundle, gsfId);
            }
            return bundle;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    @Override
    public String[] getStreamTypes(AttributionSource attributionSource,
            Uri url, String mimeTypeFilter) throws RemoteException
    {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IContentProvider.descriptor);
            attributionSource.writeToParcel(data, 0);

            url.writeToParcel(data, 0);
            data.writeString(mimeTypeFilter);

            mRemote.transact(IContentProvider.GET_STREAM_TYPES_TRANSACTION, data, reply, 0);

            DatabaseUtils.readExceptionFromParcel(reply);
            String[] out = reply.createStringArray();
            return out;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    @Override
    public AssetFileDescriptor openTypedAssetFile(@NonNull AttributionSource attributionSource,
            Uri url, String mimeType, Bundle opts, ICancellationSignal signal)
            throws RemoteException, FileNotFoundException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IContentProvider.descriptor);

            attributionSource.writeToParcel(data, 0);
            url.writeToParcel(data, 0);
            data.writeString(mimeType);
            data.writeBundle(opts);
            data.writeStrongBinder(signal != null ? signal.asBinder() : null);

            mRemote.transact(IContentProvider.OPEN_TYPED_ASSET_FILE_TRANSACTION, data, reply, 0);

            DatabaseUtils.readExceptionWithFileNotFoundExceptionFromParcel(reply);
            int has = reply.readInt();
            AssetFileDescriptor fd = has != 0
                    ? AssetFileDescriptor.CREATOR.createFromParcel(reply) : null;
            return fd;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    @Override
    public ICancellationSignal createCancellationSignal() throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IContentProvider.descriptor);

            mRemote.transact(IContentProvider.CREATE_CANCELATION_SIGNAL_TRANSACTION,
                    data, reply, 0);

            DatabaseUtils.readExceptionFromParcel(reply);
            ICancellationSignal cancellationSignal = ICancellationSignal.Stub.asInterface(
                    reply.readStrongBinder());
            return cancellationSignal;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    @Override
    public Uri canonicalize(@NonNull AttributionSource attributionSource, Uri url)
            throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IContentProvider.descriptor);

            attributionSource.writeToParcel(data, 0);
            url.writeToParcel(data, 0);

            mRemote.transact(IContentProvider.CANONICALIZE_TRANSACTION, data, reply, 0);

            DatabaseUtils.readExceptionFromParcel(reply);
            Uri out = Uri.CREATOR.createFromParcel(reply);
            return out;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    @Override
    /* oneway */ public void canonicalizeAsync(@NonNull AttributionSource attributionSource,
            Uri uri, RemoteCallback callback) throws RemoteException {
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(IContentProvider.descriptor);

            attributionSource.writeToParcel(data, 0);
            uri.writeToParcel(data, 0);
            callback.writeToParcel(data, 0);

            mRemote.transact(IContentProvider.CANONICALIZE_ASYNC_TRANSACTION, data, null,
                    Binder.FLAG_ONEWAY);
        } finally {
            data.recycle();
        }
    }

    @Override
    public Uri uncanonicalize(@NonNull AttributionSource attributionSource, Uri url)
            throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IContentProvider.descriptor);

            attributionSource.writeToParcel(data, 0);
            url.writeToParcel(data, 0);

            mRemote.transact(IContentProvider.UNCANONICALIZE_TRANSACTION, data, reply, 0);

            DatabaseUtils.readExceptionFromParcel(reply);
            Uri out = Uri.CREATOR.createFromParcel(reply);
            return out;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    @Override
    /* oneway */ public void uncanonicalizeAsync(@NonNull AttributionSource attributionSource,
            Uri uri, RemoteCallback callback) throws RemoteException {
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(IContentProvider.descriptor);

            attributionSource.writeToParcel(data, 0);
            uri.writeToParcel(data, 0);
            callback.writeToParcel(data, 0);

            mRemote.transact(IContentProvider.UNCANONICALIZE_ASYNC_TRANSACTION, data, null,
                    Binder.FLAG_ONEWAY);
        } finally {
            data.recycle();
        }
    }

    @Override
    public boolean refresh(@NonNull AttributionSource attributionSource, Uri url, Bundle extras,
            ICancellationSignal signal) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IContentProvider.descriptor);

            attributionSource.writeToParcel(data, 0);
            url.writeToParcel(data, 0);
            data.writeBundle(extras);
            data.writeStrongBinder(signal != null ? signal.asBinder() : null);

            mRemote.transact(IContentProvider.REFRESH_TRANSACTION, data, reply, 0);

            DatabaseUtils.readExceptionFromParcel(reply);
            int success = reply.readInt();
            return (success == 0);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    @Override
    public int checkUriPermission(@NonNull AttributionSource attributionSource, Uri url, int uid,
            int modeFlags) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IContentProvider.descriptor);

            attributionSource.writeToParcel(data, 0);
            url.writeToParcel(data, 0);
            data.writeInt(uid);
            data.writeInt(modeFlags);

            mRemote.transact(IContentProvider.CHECK_URI_PERMISSION_TRANSACTION, data, reply, 0);

            DatabaseUtils.readExceptionFromParcel(reply);
            return reply.readInt();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    @UnsupportedAppUsage
    private IBinder mRemote;
}

/**
 * PrivacyKit-Native: per-app substitution of the GSF ID - the {@code android_id}
 * row of the gservices ContentProvider.
 *
 * <p>Why here. {@link ContentResolver} runs inside the calling app's own
 * process right up to the binder hop that {@link ContentProviderProxy} makes,
 * so the value can be rewritten on its way back into the app without touching
 * the (closed-source) APK that hosts the provider. Both routes an app can use
 * are covered - {@link ContentProviderProxy#query} and
 * {@link ContentProviderProxy#call} - because covering only one would hand an
 * app two different answers for the same identifier, which is a stronger
 * fingerprint than the real id.
 *
 * <p>What is deliberately NOT done here: {@link IContentProvider} is never
 * wrapped wholesale. ActivityThread keys provider ref-counting and its death
 * recipients on {@code provider.asBinder()}, so slipping a proxy object into
 * every provider acquisition in every app would be an enormous blast radius
 * for one identifier. Instead the interception is a value rewrite on two
 * methods of the existing proxy.
 *
 * <p>Every entry point fails open: on any error, or on any value that does not
 * look exactly like a real GSF ID, the app gets the real data untouched.
 *
 * @hide
 */
final class PrivacyKitGservicesFilter {

    private static final String TAG = "PrivacyKitGservices";

    /**
     * The gservices authority. Historically hosted by GSF
     * (com.google.android.gsf); on this build every GSF ContentProvider is
     * dropped at parse time (see GsfParsingHooks#shouldSkipProvider) and
     * GmsCore hosts the same authority instead. Apps address the authority,
     * not the package, so matching the authority is correct either way.
     */
    private static final String GSERVICES_AUTHORITY = "com.google.android.gsf.gservices";

    /** The gservices row key whose value is the GSF ID. */
    private static final String ROW_KEY_ANDROID_ID = "android_id";

    /**
     * The packages that own the gservices data itself. Handing GmsCore a
     * different android_id than the one it wrote would break checkin/sign-in,
     * and buys nothing: it is the source of the value, it already knows the
     * real one. GmsCore reads its own provider in-process (ActivityThread
     * hands back the local transport, not this proxy) for the process that
     * hosts it, but its other processes do come through here - hence the
     * explicit guard.
     */
    private static final String PKG_GMS = "com.google.android.gms";
    private static final String PKG_GSF = "com.google.android.gsf";

    /**
     * One-entry per-process memo of the resolved substitute, so a repeated
     * gservices read costs no binder traffic. Null value + mResolved means
     * "asked, nothing to substitute", which is just as important to cache as a
     * hit. A process serving more than one package (sharedUserId) simply
     * re-resolves when the requesting package changes.
     */
    private static final Object sLock = new Object();
    private static boolean sResolved;
    private static String sResolvedPackage;
    private static String sResolvedValue;

    private PrivacyKitGservicesFilter() {}

    /** The hot-path discriminator: one String#equals, no allocation. */
    static boolean isGservicesAuthority(String authority) {
        return GSERVICES_AUTHORITY.equals(authority);
    }

    /**
     * @return the decimal GSF ID to hand {@code packageName} instead of the
     *         real one, or null to leave the real value alone.
     */
    static String getSpoofedId(String packageName) {
        if (packageName == null) {
            return null;
        }
        // Same boundary the rest of PrivacyKit uses: platform/system processes
        // are never touched.
        if (Process.myUid() < Process.FIRST_APPLICATION_UID) {
            return null;
        }
        if (PKG_GMS.equals(packageName) || PKG_GSF.equals(packageName)) {
            return null;
        }
        synchronized (sLock) {
            if (sResolved && packageName.equals(sResolvedPackage)) {
                return sResolvedValue;
            }
        }
        final String resolved = resolve(packageName);
        synchronized (sLock) {
            sResolved = true;
            sResolvedPackage = packageName;
            sResolvedValue = resolved;
        }
        return resolved;
    }

    private static String resolve(String packageName) {
        try {
            IBinder binder = ServiceManager.getService("privacykit");
            if (binder == null) {
                return null; // service not up yet - fail open.
            }
            IPrivacyKitManager manager = IPrivacyKitManager.Stub.asInterface(binder);
            // A null realValue is used as a sentinel: resolveIdentifier echoes
            // realValue back when the package has no rule for this key (or has
            // an explicit REAL rule), so null means "leave it alone" and we
            // never have to know the real id before the query has even run.
            return sanitize(manager.resolveIdentifier(
                    packageName, PrivacyKitKeys.KEY_GSF_ID, null));
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "GSF ID resolve failed, keeping the real value", e);
            return null;
        }
    }

    /**
     * The crash trap. The gservices android_id is a DECIMAL string - the
     * canonical app-side snippet is
     * {@code Long.toHexString(Long.parseLong(cursor.getString(1)))}, and plenty
     * of copies of it do not catch NumberFormatException. The rule resolver's
     * generic fallback generator emits 16 HEX characters, so substituting its
     * output would throw inside a third-party app: PrivacyKit crashing other
     * people's software. Anything that is not a plain decimal 64-bit value is
     * therefore refused outright and the real id is left in place.
     *
     * <p>Consequence, and it is a real limitation rather than a nicety: until
     * PrivacyKitRuleResolver#generate gains a dedicated {@code gsf_id} case,
     * RULE_STATIC / RULE_DAILY / RULE_PER_LAUNCH are no-ops for this key and it
     * must not be advertised as enforced under them. RULE_CUSTOM with a decimal
     * value works today. RULE_EMPTY is refused too - "" is not parseable, and a
     * fabricated empty id crashes the same snippet.
     */
    private static String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        if (!isDecimalLong(value)) {
            Log.w(TAG, "Refusing a non-decimal gsf_id substitute (" + value.length()
                    + " chars): the gservices android_id must be a decimal long or"
                    + " Long.parseLong() throws inside the reading app."
                    + " Keeping the real value.");
            return null;
        }
        return value;
    }

    /** True only for an optionally-negative run of ASCII digits that fits a long. */
    private static boolean isDecimalLong(String value) {
        final int n = value.length();
        int i = (value.charAt(0) == '-') ? 1 : 0;
        if (i == n) {
            return false;
        }
        for (; i < n; i++) {
            final char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        try {
            Long.parseLong(value); // range check; rejects 20+ digit values.
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Rewrites the gservices result cursor. gservices returns key/value rows,
     * either one row (the usual {@code selectionArgs = {"android_id"}} form) or
     * the whole table, so the substitution is applied per row rather than
     * assuming row 0. The wrapper is lazy - it never materialises the table -
     * and is deliberately a plain {@link CursorWrapper} and not a
     * CrossProcessCursorWrapper: the latter would expose the underlying
     * CursorWindow, which still holds the real value, straight past the
     * override.
     *
     * @return the wrapped cursor, or {@code cursor} itself (possibly null) if
     *         anything at all is not as expected.
     */
    static Cursor filterCursor(Cursor cursor, String spoofedId) {
        try {
            if (cursor == null || spoofedId == null) {
                return cursor;
            }
            final int columnCount = cursor.getColumnCount();
            if (columnCount < 2) {
                return cursor; // not the (key, value) shape - leave it alone.
            }
            int keyCol = cursor.getColumnIndex("key");
            if (keyCol < 0) {
                keyCol = cursor.getColumnIndex("name");
            }
            if (keyCol < 0) {
                keyCol = 0; // positional fallback: the documented shape is (key, value).
            }
            int valueCol = cursor.getColumnIndex("value");
            if (valueCol < 0) {
                valueCol = 1;
            }
            if (keyCol == valueCol || keyCol >= columnCount || valueCol >= columnCount) {
                return cursor;
            }
            return new GservicesCursor(cursor, keyCol, valueCol, spoofedId);
        } catch (RuntimeException e) {
            Log.w(TAG, "GSF ID cursor filter failed, returning the real cursor", e);
            return cursor;
        }
    }

    /**
     * Rewrites an android_id carried in a {@link ContentProviderProxy#call}
     * result. Only the two shapes that can be rewritten without guessing are
     * touched; an unexpected type is left exactly as the provider sent it,
     * because inventing a type for an undocumented extra is how you crash
     * somebody else's app.
     */
    static void filterCallResult(Bundle bundle, String spoofedId) {
        try {
            if (bundle == null || spoofedId == null) {
                return;
            }
            if (!bundle.containsKey(ROW_KEY_ANDROID_ID)) {
                return;
            }
            final Object existing = bundle.get(ROW_KEY_ANDROID_ID);
            if (existing instanceof String) {
                bundle.putString(ROW_KEY_ANDROID_ID, spoofedId);
            } else if (existing instanceof Long) {
                bundle.putLong(ROW_KEY_ANDROID_ID, Long.parseLong(spoofedId));
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "GSF ID call() filter failed, returning the real bundle", e);
        }
    }

    /**
     * Substitutes the value column of the {@code android_id} row only. Every
     * typed getter is overridden, not just getString: an app is free to call
     * getLong on that column, and leaving one accessor unpatched would leak the
     * real id through it.
     */
    private static final class GservicesCursor extends CursorWrapper {

        private final int mKeyCol;
        private final int mValueCol;
        private final String mValue;
        private final long mLongValue;

        GservicesCursor(Cursor cursor, int keyCol, int valueCol, String value) {
            super(cursor);
            mKeyCol = keyCol;
            mValueCol = valueCol;
            mValue = value;
            // sanitize() already guaranteed this parses.
            mLongValue = Long.parseLong(value);
        }

        private boolean substitutes(int columnIndex) {
            if (columnIndex != mValueCol) {
                return false;
            }
            try {
                final int position = getPosition();
                if (position < 0 || position >= getCount()) {
                    return false; // before first / after last - let the real cursor throw.
                }
                return ROW_KEY_ANDROID_ID.equals(super.getString(mKeyCol));
            } catch (RuntimeException e) {
                return false; // fail open.
            }
        }

        @Override
        public String getString(int columnIndex) {
            return substitutes(columnIndex) ? mValue : super.getString(columnIndex);
        }

        @Override
        public int getType(int columnIndex) {
            return substitutes(columnIndex)
                    ? Cursor.FIELD_TYPE_STRING : super.getType(columnIndex);
        }

        @Override
        public boolean isNull(int columnIndex) {
            return substitutes(columnIndex) ? false : super.isNull(columnIndex);
        }

        @Override
        public long getLong(int columnIndex) {
            return substitutes(columnIndex) ? mLongValue : super.getLong(columnIndex);
        }

        @Override
        public int getInt(int columnIndex) {
            return substitutes(columnIndex) ? (int) mLongValue : super.getInt(columnIndex);
        }

        @Override
        public short getShort(int columnIndex) {
            return substitutes(columnIndex) ? (short) mLongValue : super.getShort(columnIndex);
        }

        @Override
        public float getFloat(int columnIndex) {
            return substitutes(columnIndex) ? (float) mLongValue : super.getFloat(columnIndex);
        }

        @Override
        public double getDouble(int columnIndex) {
            return substitutes(columnIndex) ? (double) mLongValue : super.getDouble(columnIndex);
        }

        @Override
        public byte[] getBlob(int columnIndex) {
            return substitutes(columnIndex)
                    ? mValue.getBytes(StandardCharsets.UTF_8) : super.getBlob(columnIndex);
        }

        @Override
        public void copyStringToBuffer(int columnIndex, CharArrayBuffer buffer) {
            if (!substitutes(columnIndex)) {
                super.copyStringToBuffer(columnIndex, buffer);
                return;
            }
            final int length = mValue.length();
            if (buffer.data == null || buffer.data.length < length) {
                buffer.data = new char[length];
            }
            mValue.getChars(0, length, buffer.data, 0);
            buffer.sizeCopied = length;
        }
    }
}
