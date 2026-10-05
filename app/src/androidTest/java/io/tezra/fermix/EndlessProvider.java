package io.tezra.fermix;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.util.Log;
import io.tezra.fermix.chat.LandingsKt;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Another app's provider, the test APK's own, of a package and a uid other than the app's, serving the worst another
 * app may hand a share: an item of no size it says, whose stream goes on handing over zeros as long as it is read,
 * until {@link #ENDLESS_STOP_BYTES}, and stops as its reader closes it; an item named and typed past any bound of the
 * app's ({@link #LONG_NAMED_URI}); and an item whose stream trickles a byte at a time and never ends
 * ({@link #TRICKLE_URI}), until its reader closes it. How many bytes the endless item handed over since it was last
 * opened is asked with {@link #call} ({@link #HANDED}), and whether the trickle's reader closed its stream
 * ({@link #TRICKLE_CLOSED}).
 *
 * <p>It is Java, the repository's one Java file: the platform starts it in the test APK's own process, which holds the
 * test APK's classes alone, not the app's, and the build leaves the Kotlin runtime out of the test APK as the app
 * already has it, so a provider written in Kotlin dies there on its first call ("Didn't find class
 * kotlin.jvm.internal.Intrinsics"). Its byte bound is the app's own constant, which the compiler copies in.
 */
public final class EndlessProvider extends ContentProvider {
    /** The provider's authority, as the test APK's manifest declares it. */
    private static final String AUTHORITY = "io.tezra.fermix.test.endless";

    /** The name the one item it serves is given. */
    public static final String ENDLESS_NAME = "endless.bin";

    /** The one item it serves. */
    public static final Uri ENDLESS_URI = Uri.parse("content://" + AUTHORITY + "/" + ENDLESS_NAME);

    /** The call that asks how many bytes the item's stream handed over, and the key the answer holds them under. */
    public static final String HANDED = "handed";

    /** How long the long-named item's name is: past a frame's header, and past what a saved state's parcel holds. */
    public static final int LONG_NAME_CHARS = 600_000;

    /** The item named and typed past any bound. */
    public static final Uri LONG_NAMED_URI = Uri.parse("content://" + AUTHORITY + "/long-named");

    /** The bytes the long-named item's stream hands over before it ends. */
    private static final int LONG_NAMED_BYTES = 16;

    /** The name the trickling item is given. */
    public static final String TRICKLE_NAME = "trickle.bin";

    /** The item whose stream trickles. */
    public static final Uri TRICKLE_URI = Uri.parse("content://" + AUTHORITY + "/" + TRICKLE_NAME);

    /** The call that asks whether the trickle's reader closed its stream, and the key the answer holds it under. */
    public static final String TRICKLE_CLOSED = "trickle_closed";

    /** How long the trickle waits between its bytes. */
    private static final long TRICKLE_STEP_MILLIS = 200;

    /** The trickle's bytes at most, ten minutes of steps, past any landing's time: its loop's one bound. */
    private static final int TRICKLE_MAX_STEPS = 3_000;

    /**
     * Where the stream stops, which no landing copy should ever reach: four times the most one holds, so a copy with no
     * bound of its own fails its test, not the phone's storage.
     */
    private static final long ENDLESS_STOP_BYTES = 4 * LandingsKt.LANDING_MAX_BYTES;

    /** What one write hands over. */
    private static final int CHUNK_BYTES = 64 * 1024;

    private static final String TAG = "EndlessProvider";

    private final AtomicLong handed = new AtomicLong(0);

    private final AtomicBoolean trickleClosed = new AtomicBoolean(false);

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(
            Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        servedOnly(uri);
        MatrixCursor row = new MatrixCursor(new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        if (LONG_NAMED_URI.equals(uri)) {
            row.addRow(new Object[] {repeated('n', LONG_NAME_CHARS) + ".bin", LONG_NAMED_BYTES});
        } else {
            row.addRow(new Object[] {TRICKLE_URI.equals(uri) ? TRICKLE_NAME : ENDLESS_NAME, null});
        }
        return row;
    }

    @Override
    public String getType(Uri uri) {
        return LONG_NAMED_URI.equals(uri) ? "application/" + repeated('x', LONG_NAME_CHARS) : "application/octet-stream";
    }

    /** The item's stream: a pipe a thread of the provider's own writes into until it stops or its reader goes. */
    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        servedOnly(uri);
        if (!"r".equals(mode)) {
            throw new IllegalArgumentException("the endless item is only read, not opened " + mode);
        }
        ParcelFileDescriptor[] pipe = pipe();
        if (LONG_NAMED_URI.equals(uri)) {
            new Thread(() -> longNamedOver(pipe[1]), "long-named-stream").start();
        } else if (TRICKLE_URI.equals(uri)) {
            trickleClosed.set(false);
            new Thread(() -> trickleOver(pipe[1]), "trickle-stream").start();
        } else {
            handed.set(0);
            new Thread(() -> handOver(pipe[1]), "endless-stream").start();
        }
        return pipe[0];
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle answer = new Bundle();
        if (HANDED.equals(method)) {
            answer.putLong(HANDED, handed.get());
        } else if (TRICKLE_CLOSED.equals(method)) {
            answer.putBoolean(TRICKLE_CLOSED, trickleClosed.get());
        } else {
            throw new IllegalArgumentException("no call " + method);
        }
        return answer;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("the endless item is read only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("the endless item is read only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("the endless item is read only");
    }

    /** Refuses any {@code uri} but the items it serves. */
    private static void servedOnly(Uri uri) {
        if (!ENDLESS_URI.equals(uri) && !LONG_NAMED_URI.equals(uri) && !TRICKLE_URI.equals(uri)) {
            throw new IllegalArgumentException("no item " + uri);
        }
    }

    /** {@code letter} {@code count} times. */
    private static String repeated(char letter, int count) {
        char[] letters = new char[count];
        Arrays.fill(letters, letter);
        return new String(letters);
    }

    /** A pipe's read and write ends; the platform's failure to make one, as the provider's open fails. */
    private static ParcelFileDescriptor[] pipe() throws FileNotFoundException {
        try {
            return ParcelFileDescriptor.createPipe();
        } catch (IOException failed) {
            FileNotFoundException notOpened = new FileNotFoundException("no pipe for the endless item");
            notOpened.initCause(failed);
            throw notOpened;
        }
    }

    /** {@code write} handed zeros ({@link #zerosInto}) until its reader closes the pipe or they stop; closed then. */
    private void handOver(ParcelFileDescriptor write) {
        try (OutputStream into = new ParcelFileDescriptor.AutoCloseOutputStream(write)) {
            zerosInto(into);
        } catch (IOException closed) {
            Log.i(TAG, "the reader closed the stream after " + handed.get() + " bytes", closed);
        }
    }

    /** The long-named item's few bytes into {@code write}, which is closed then. */
    private static void longNamedOver(ParcelFileDescriptor write) {
        try (OutputStream into = new ParcelFileDescriptor.AutoCloseOutputStream(write)) {
            into.write(new byte[LONG_NAMED_BYTES]);
        } catch (IOException closed) {
            Log.i(TAG, "the long-named item's reader closed its stream first", closed);
        }
    }

    /**
     * A byte into {@code write} every {@link #TRICKLE_STEP_MILLIS}, at most {@link #TRICKLE_MAX_STEPS}, until a write
     * fails as its reader closed the pipe, which {@link #trickleClosed} then says.
     */
    private void trickleOver(ParcelFileDescriptor write) {
        try (OutputStream into = new ParcelFileDescriptor.AutoCloseOutputStream(write)) {
            for (int step = 0; step < TRICKLE_MAX_STEPS; step++) {
                into.write(0);
                into.flush();
                // The trickle's own pace, a span the test defines: a provider that is slow, not one that is done.
                SystemClock.sleep(TRICKLE_STEP_MILLIS);
            }
        } catch (IOException closed) {
            trickleClosed.set(true);
            Log.i(TAG, "the trickle's reader closed its stream", closed);
        }
    }

    /** Zeros into {@code into}, a chunk at a time, until {@link #ENDLESS_STOP_BYTES} went: the loop's one bound. */
    private void zerosInto(OutputStream into) throws IOException {
        byte[] chunk = new byte[CHUNK_BYTES];
        while (handed.get() < ENDLESS_STOP_BYTES) {
            into.write(chunk);
            handed.addAndGet(CHUNK_BYTES);
        }
    }
}
