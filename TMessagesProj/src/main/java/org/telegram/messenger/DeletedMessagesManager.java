/*
 * Deleted Messages Manager
 * Auto-saves deleted messages and allows viewing them
 * Feature is always enabled by default
 */
package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.SQLite.SQLitePreparedStatement;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages auto-saving of deleted messages.
 * This feature is always enabled and works transparently.
 */
public class DeletedMessagesManager {

    private static final String TAG = "DeletedMessagesManager";
    private static final String PREFS_NAME = "deleted_messages_prefs";
    private static final String KEY_AUTO_SAVE_ENABLED = "auto_save_enabled";
    private static final String KEY_FEATURE_VERSION = "feature_version";
    private static final int CURRENT_FEATURE_VERSION = 2;

    private final int currentAccount;
    private final ExecutorService executor;
    private static final HashMap<Integer, DeletedMessagesManager> instances = new HashMap<>();

    // Feature is ALWAYS enabled - no user control
    private static final boolean ALWAYS_ENABLED = true;

    /**
     * Get singleton instance for account
     */
    public static DeletedMessagesManager getInstance(int account) {
        DeletedMessagesManager instance = instances.get(account);
        if (instance == null) {
            synchronized (DeletedMessagesManager.class) {
                instance = instances.get(account);
                if (instance == null) {
                    instance = new DeletedMessagesManager(account);
                    instances.put(account, instance);
                }
            }
        }
        return instance;
    }

    private DeletedMessagesManager(int account) {
        this.currentAccount = account;
        this.executor = Executors.newSingleThreadExecutor();
        ensureDatabaseTables();
        migrateIfNeeded();
    }

    /**
     * Create database tables for storing deleted messages
     */
    private void ensureDatabaseTables() {
        executor.execute(() -> {
            try {
                SQLiteDatabase database = MessagesStorage.getInstance(currentAccount).getDatabase();
                if (database != null) {
                    // Main table for deleted messages
                    database.executeFast(
                        "CREATE TABLE IF NOT EXISTS deleted_messages_v2 (" +
                        "mid INTEGER, " +
                        "uid INTEGER, " +
                        "data BLOB, " +
                        "date INTEGER, " +
                        "from_id INTEGER, " +
                        "dialog_type INTEGER DEFAULT 0, " +
                        "media_type INTEGER DEFAULT 0, " +
                        "saved_at INTEGER, " +
                        "PRIMARY KEY(mid, uid))"
                    ).stepThis().dispose();

                    // Indexes for fast queries
                    database.executeFast(
                        "CREATE INDEX IF NOT EXISTS deleted_messages_uid_idx " +
                        "ON deleted_messages_v2(uid)"
                    ).stepThis().dispose();

                    database.executeFast(
                        "CREATE INDEX IF NOT EXISTS deleted_messages_date_idx " +
                        "ON deleted_messages_v2(date DESC)"
                    ).stepThis().dispose();

                    database.executeFast(
                        "CREATE INDEX IF NOT EXISTS deleted_messages_saved_at_idx " +
                        "ON deleted_messages_v2(saved_at DESC)"
                    ).stepThis().dispose();

                    // Table for message media files info
                    database.executeFast(
                        "CREATE TABLE IF NOT EXISTS deleted_messages_media (" +
                        "mid INTEGER PRIMARY KEY, " +
                        "uid INTEGER, " +
                        "media_path TEXT, " +
                        "media_type INTEGER, " +
                        "mime_type TEXT, " +
                        "file_size INTEGER)"
                    ).stepThis().dispose();

                    FileLog.d(TAG + ": Database tables created/verified");
                }
            } catch (Exception e) {
                FileLog.e(TAG + ": Error creating tables", e);
            }
        });
    }

    /**
     * Migrate old data if needed
     */
    private void migrateIfNeeded() {
        try {
            SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences(
                PREFS_NAME + "_" + currentAccount, Context.MODE_PRIVATE);
            int version = prefs.getInt(KEY_FEATURE_VERSION, 0);
            if (version < CURRENT_FEATURE_VERSION) {
                // Run migrations
                prefs.edit().putInt(KEY_FEATURE_VERSION, CURRENT_FEATURE_VERSION).apply();
                FileLog.d(TAG + ": Migrated to version " + CURRENT_FEATURE_VERSION);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /**
     * Check if auto-save is enabled (always returns true - feature is always on)
     */
    public boolean isAutoSaveEnabled() {
        return ALWAYS_ENABLED;
    }

    /**
     * Save a single deleted message
     */
    public void saveDeletedMessage(TLRPC.Message message, long dialogId) {
        if (message == null) {
            return;
        }

        executor.execute(() -> {
            try {
                SQLiteDatabase database = MessagesStorage.getInstance(currentAccount).getDatabase();
                if (database == null) {
                    FileLog.w(TAG + ": Database not available");
                    return;
                }

                // Serialize message
                int size = message.getObjectSize();
                if (size <= 0) {
                    FileLog.w(TAG + ": Invalid message size");
                    return;
                }

                NativeByteBuffer data = new NativeByteBuffer(size);
                message.serializeToStream(data);

                // Determine message type
                int mediaType = getMediaType(message);
                int dialogType = getDialogType(dialogId);

                // Insert into database
                SQLitePreparedStatement state = database.executeFast(
                    "REPLACE INTO deleted_messages_v2 " +
                    "(mid, uid, data, date, from_id, dialog_type, media_type, saved_at) " +
                    "VALUES(?, ?, ?, ?, ?, ?, ?, ?)"
                );

                state.requery();
                state.bindInteger(1, message.id);
                state.bindLong(2, dialogId);
                state.bindByteBuffer(3, data);
                state.bindInteger(4, message.date);
                state.bindLong(5, MessageObject.getFromChatId(message));
                state.bindInteger(6, dialogType);
                state.bindInteger(7, mediaType);
                state.bindLong(8, System.currentTimeMillis());
                state.step();
                state.dispose();

                data.reuse();

                // Save media info if applicable
                if (message.media != null && mediaType > 0) {
                    saveMediaInfo(database, message, dialogId);
                }

                FileLog.d(TAG + ": Saved deleted message " + message.id +
                    " from dialog " + dialogId + " mediaType=" + mediaType);

            } catch (Exception e) {
                FileLog.e(TAG + ": Error saving deleted message", e);
            }
        });
    }

    /**
     * Save multiple deleted messages at once
     */
    public void saveDeletedMessages(ArrayList<TLRPC.Message> messages, long dialogId) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        for (TLRPC.Message message : messages) {
            saveDeletedMessage(message, dialogId);
        }
    }

    /**
     * Save media information for a message
     */
    private void saveMediaInfo(SQLiteDatabase database, TLRPC.Message message, long dialogId) {
        try {
            if (message.media == null) return;

            String mediaPath = null;
            String mimeType = null;
            long fileSize = 0;
            int mediaType = getMediaType(message);

            // Get media path and info
            if (message.media.photo != null) {
                mimeType = "image/jpeg";
                for (TLRPC.PhotoSize size : message.media.photo.sizes) {
                    if (size instanceof TLRPC.TL_photoSize) {
                        fileSize = size.size;
                        break;
                    }
                }
            } else if (message.media.document != null) {
                TLRPC.Document doc = message.media.document;
                mimeType = doc.mime_type;
                fileSize = doc.size;

                // Try to get local path
                for (TLRPC.DocumentAttribute attr : doc.attributes) {
                    if (attr instanceof TLRPC.TL_documentAttributeFilename) {
                        // File was possibly downloaded
                        break;
                    }
                }
            }

            SQLitePreparedStatement state = database.executeFast(
                "REPLACE INTO deleted_messages_media " +
                "(mid, uid, media_path, media_type, mime_type, file_size) " +
                "VALUES(?, ?, ?, ?, ?, ?)"
            );

            state.requery();
            state.bindInteger(1, message.id);
            state.bindLong(2, dialogId);
            state.bindString(3, mediaPath != null ? mediaPath : "");
            state.bindInteger(4, mediaType);
            state.bindString(5, mimeType != null ? mimeType : "");
            state.bindLong(6, fileSize);
            state.step();
            state.dispose();

        } catch (Exception e) {
            FileLog.e(TAG + ": Error saving media info", e);
        }
    }

    /**
     * Get media type from message
     */
    private int getMediaType(TLRPC.Message message) {
        if (message == null || message.media == null) {
            return 0;
        }

        if (message.media instanceof TLRPC.TL_messageMediaPhoto) {
            return 1; // Photo
        } else if (message.media instanceof TLRPC.TL_messageMediaDocument) {
            if (message.media.document != null) {
                // Check for video
                for (TLRPC.DocumentAttribute attr : message.media.document.attributes) {
                    if (attr instanceof TLRPC.TL_documentAttributeVideo) {
                        return 3; // Video
                    } else if (attr instanceof TLRPC.TL_documentAttributeSticker) {
                        return 4; // Sticker
                    } else if (attr instanceof TLRPC.TL_documentAttributeAudio) {
                        if (attr.voice) {
                            return 5; // Voice
                        }
                        return 6; // Audio
                    } else if (attr instanceof TLRPC.TL_documentAttributeAnimated) {
                        return 7; // GIF
                    }
                }
                return 2; // Document
            }
        } else if (message.media instanceof TLRPC.TL_messageMediaGeo) {
            return 8; // Location
        } else if (message.media instanceof TLRPC.TL_messageMediaContact) {
            return 9; // Contact
        } else if (message.media instanceof TLRPC.TL_messageMediaPoll) {
            return 10; // Poll
        } else if (message.media instanceof TLRPC.TL_messageMediaWebPage) {
            return 11; // Web page preview
        }

        return 0;
    }

    /**
     * Get dialog type
     */
    private int getDialogType(long dialogId) {
        int lowerId = (int) dialogId;
        if (lowerId > 0) {
            return 0; // User
        } else if (DialogObject.isChannel(dialogId)) {
            return 2; // Channel
        } else {
            return 1; // Group
        }
    }

    /**
     * Get deleted messages for a dialog
     */
    public ArrayList<TLRPC.Message> getDeletedMessages(long dialogId, int offset, int count) {
        ArrayList<TLRPC.Message> messages = new ArrayList<>();
        SQLiteCursor cursor = null;

        try {
            SQLiteDatabase database = MessagesStorage.getInstance(currentAccount).getDatabase();
            if (database == null) {
                return messages;
            }

            cursor = database.queryFinalized(
                "SELECT data FROM deleted_messages_v2 " +
                "WHERE uid = " + dialogId + " " +
                "ORDER BY date DESC LIMIT " + count + " OFFSET " + offset
            );

            long currentUser = UserConfig.getInstance(currentAccount).getClientUserId();

            while (cursor.next()) {
                NativeByteBuffer data = cursor.byteBufferValue(0);
                if (data != null) {
                    try {
                        TLRPC.Message message = TLRPC.Message.TLdeserialize(
                            data, data.readInt32(false), false
                        );
                        if (message != null) {
                            message.readAttachPath(data, currentUser);
                            messages.add(message);
                        }
                    } catch (Exception e) {
                        FileLog.e(TAG + ": Error deserializing message", e);
                    }
                    data.reuse();
                }
            }
        } catch (Exception e) {
            FileLog.e(TAG + ": Error getting deleted messages", e);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }

        return messages;
    }

    /**
     * Get a specific deleted message
     */
    public TLRPC.Message getDeletedMessage(long dialogId, int messageId) {
        SQLiteCursor cursor = null;

        try {
            SQLiteDatabase database = MessagesStorage.getInstance(currentAccount).getDatabase();
            if (database == null) {
                return null;
            }

            cursor = database.queryFinalized(
                "SELECT data FROM deleted_messages_v2 " +
                "WHERE uid = " + dialogId + " AND mid = " + messageId
            );

            if (cursor.next()) {
                NativeByteBuffer data = cursor.byteBufferValue(0);
                if (data != null) {
                    TLRPC.Message message = TLRPC.Message.TLdeserialize(
                        data, data.readInt32(false), false
                    );
                    data.reuse();
                    return message;
                }
            }
        } catch (Exception e) {
            FileLog.e(TAG + ": Error getting deleted message", e);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }

        return null;
    }

    /**
     * Get count of deleted messages in a dialog
     */
    public int getDeletedMessagesCount(long dialogId) {
        SQLiteCursor cursor = null;
        int count = 0;

        try {
            SQLiteDatabase database = MessagesStorage.getInstance(currentAccount).getDatabase();
            if (database == null) {
                return 0;
            }

            cursor = database.queryFinalized(
                "SELECT COUNT(*) FROM deleted_messages_v2 WHERE uid = " + dialogId
            );

            if (cursor.next()) {
                count = cursor.intValue(0);
            }
        } catch (Exception e) {
            FileLog.e(TAG + ": Error getting count", e);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }

        return count;
    }

    /**
     * Get total count of all deleted messages
     */
    public int getTotalDeletedMessagesCount() {
        SQLiteCursor cursor = null;
        int count = 0;

        try {
            SQLiteDatabase database = MessagesStorage.getInstance(currentAccount).getDatabase();
            if (database == null) {
                return 0;
            }

            cursor = database.queryFinalized(
                "SELECT COUNT(*) FROM deleted_messages_v2"
            );

            if (cursor.next()) {
                count = cursor.intValue(0);
            }
        } catch (Exception e) {
            FileLog.e(TAG + ": Error getting total count", e);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }

        return count;
    }

    /**
     * Get dialogs that have deleted messages
     */
    public ArrayList<Long> getDialogsWithDeletedMessages() {
        ArrayList<Long> dialogs = new ArrayList<>();
        SQLiteCursor cursor = null;

        try {
            SQLiteDatabase database = MessagesStorage.getInstance(currentAccount).getDatabase();
            if (database == null) {
                return dialogs;
            }

            cursor = database.queryFinalized(
                "SELECT DISTINCT uid FROM deleted_messages_v2 ORDER BY saved_at DESC"
            );

            while (cursor.next()) {
                dialogs.add(cursor.longValue(0));
            }
        } catch (Exception e) {
            FileLog.e(TAG + ": Error getting dialogs", e);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }

        return dialogs;
    }

    /**
     * Clear deleted messages for a specific dialog
     */
    public void clearDeletedMessages(long dialogId) {
        executor.execute(() -> {
            try {
                SQLiteDatabase database = MessagesStorage.getInstance(currentAccount).getDatabase();
                if (database == null) {
                    return;
                }

                database.executeFast(
                    "DELETE FROM deleted_messages_v2 WHERE uid = " + dialogId
                ).stepThis().dispose();

                database.executeFast(
                    "DELETE FROM deleted_messages_media WHERE uid = " + dialogId
                ).stepThis().dispose();

                FileLog.d(TAG + ": Cleared deleted messages for dialog " + dialogId);

            } catch (Exception e) {
                FileLog.e(TAG + ": Error clearing deleted messages", e);
            }
        });
    }

    /**
     * Clear all deleted messages
     */
    public void clearAllDeletedMessages() {
        executor.execute(() -> {
            try {
                SQLiteDatabase database = MessagesStorage.getInstance(currentAccount).getDatabase();
                if (database == null) {
                    return;
                }

                database.executeFast("DELETE FROM deleted_messages_v2").stepThis().dispose();
                database.executeFast("DELETE FROM deleted_messages_media").stepThis().dispose();

                FileLog.d(TAG + ": Cleared all deleted messages");

            } catch (Exception e) {
                FileLog.e(TAG + ": Error clearing all deleted messages", e);
            }
        });
    }

    /**
     * Check if a message was deleted
     */
    public boolean isMessageDeleted(long dialogId, int messageId) {
        SQLiteCursor cursor = null;

        try {
            SQLiteDatabase database = MessagesStorage.getInstance(currentAccount).getDatabase();
            if (database == null) {
                return false;
            }

            cursor = database.queryFinalized(
                "SELECT 1 FROM deleted_messages_v2 WHERE uid = " + dialogId +
                " AND mid = " + messageId + " LIMIT 1"
            );

            return cursor.next();

        } catch (Exception e) {
            FileLog.e(TAG + ": Error checking message", e);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }

        return false;
    }

    /**
     * Delete a saved deleted message
     */
    public void deleteSavedMessage(long dialogId, int messageId) {
        executor.execute(() -> {
            try {
                SQLiteDatabase database = MessagesStorage.getInstance(currentAccount).getDatabase();
                if (database == null) {
                    return;
                }

                database.executeFast(
                    "DELETE FROM deleted_messages_v2 WHERE uid = " + dialogId +
                    " AND mid = " + messageId
                ).stepThis().dispose();

                database.executeFast(
                    "DELETE FROM deleted_messages_media WHERE uid = " + dialogId +
                    " AND mid = " + messageId
                ).stepThis().dispose();

            } catch (Exception e) {
                FileLog.e(TAG + ": Error deleting saved message", e);
            }
        });
    }

    /**
     * Cleanup when account is removed
     */
    public void cleanup() {
        clearAllDeletedMessages();
        instances.remove(currentAccount);
        executor.shutdown();
    }

    /**
     * Get statistics about deleted messages
     */
    public String getStatistics() {
        int total = getTotalDeletedMessagesCount();
        int dialogsCount = getDialogsWithDeletedMessages().size();
        return String.format("Total deleted messages: %d in %d dialogs", total, dialogsCount);
    }
}
