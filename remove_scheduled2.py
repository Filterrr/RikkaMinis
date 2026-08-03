import sys, io

def sub(path, old, new, expect=1):
    with io.open(path, encoding='utf-8') as f:
        s = f.read()
    n = s.count(old)
    if n != expect:
        print("FAIL %s: expected %d found %d for: %r" % (path, expect, n, old[:80]))
        sys.exit(1)
    s = s.replace(old, new)
    with io.open(path, 'w', encoding='utf-8') as f:
        f.write(s)
    print("OK %s: replaced %d" % (path, n))

def drop_lines(path, pred, expect):
    with io.open(path, encoding='utf-8') as f:
        lines = f.readlines()
    kept = [l for l in lines if not pred(l)]
    removed = len(lines) - len(kept)
    if removed < expect:
        print("FAIL %s: expected at least %d removed, got %d" % (path, expect, removed))
        sys.exit(1)
    with io.open(path, 'w', encoding='utf-8') as f:
        f.writelines(kept)
    print("OK %s: dropped %d lines" % (path, removed))

# --- 1. ConfigBackup: Stage 8 block by markers ---
P = 'src/android/app/src/main/java/com/openminis/app/backup/ConfigBackup.kt'
lines = io.open(P, encoding='utf-8').readlines()
start = next(i for i, l in enumerate(lines) if '// -- Stage 8:' in l)
end = next(i for i, l in enumerate(lines) if 'not restorable here' in l)
del lines[start:end + 2]  # include the closing '}' of the else-if
io.open(P, 'w', encoding='utf-8').writelines(lines)
print('OK ConfigBackup: Stage 8 block removed (lines %d..%d)' % (start + 1, end + 2))

# --- 2. ConfigBackup: ImportResult field ---
sub(P, '            scheduledTasksImported = scheduledTasksImported,\n', '')

# --- 3. ConfigBackup: import log line ---
sub(P,
    '                    "mcpServers=$mcpServersImported scheduledTasks=$scheduledTasksImported " +\n',
    '                    "mcpServers=$mcpServersImported " +\n')

# --- 4. AndroidManifest: receiver block ---
sub('src/android/app/src/main/AndroidManifest.xml',
    '''        <!-- [T-android-scheduled-tasks-design] AlarmManager target for
             user-defined scheduled tasks (Settings → home top-right
             schedule icon). The receiver dispatches to ScheduledAgentRunner
             which drives the agent loop headlessly. -->
        <receiver
            android:name=".scheduled.ScheduledTaskAlarmReceiver"
            android:exported="false" />

''', '')

# --- 5. ConfigBackupPayloadTest ---
TP = 'src/android/app/src/test/java/com/openminis/app/backup/ConfigBackupPayloadTest.kt'
sub(TP, ' * servers and scheduled tasks.', ' * servers.')
sub(TP, '// Backups written before skills/memory/MCP/scheduled were covered have',
        '// Backups written before skills/memory/MCP were covered have')
sub(TP, '        assertNull(old.optJSONArray("scheduledTasks"))\n', '')
sub(TP,
    '''    @Test
    fun `scheduled task payload drops device-local run history`() {
        // Run records point at session ids that don't exist on the target
        // install; a restored task should fire in future, not carry a log.
        val task = JSONObject()
            .put("id", "t1")
            .put("label", "morning brief")
            .put("prompt", "summarize")
            .put("runs", JSONArray().put(JSONObject().put("firedAt", 123L)))
            .put("lastFiredAt", 123L)
            .put("lastResultPreview", "…")
            .put("lastResultSessionId", "sess-local")

        for (k in listOf("runs", "lastFiredAt", "lastResultPreview", "lastResultSessionId")) {
            task.remove(k)
        }

        assertFalse(task.has("runs"))
        assertFalse(task.has("lastFiredAt"))
        assertFalse(task.has("lastResultSessionId"))
        // The scheduling definition itself must survive.
        assertEquals("t1", task.optString("id"))
        assertEquals("morning brief", task.optString("label"))
        assertEquals("summarize", task.optString("prompt"))
    }

''', '')

# --- 6. strings.xml x4 ---
for lang in ['values', 'values-zh', 'values-zh-rTW', 'values-ru']:
    SP = 'src/android/app/src/main/res/%s/strings.xml' % lang
    drop_lines(SP, lambda l: (
        l.lstrip().startswith('<string name="scheduled_') or
        l.lstrip().startswith('<string name="sessionlist_scheduled_tasks"') or
        l.lstrip().startswith('<string name="backup_done_scheduled_tasks"') or
        l.lstrip().startswith('<!-- [T-android-scheduled-tasks')
    ), 1)

sub('src/android/app/src/main/res/values/strings.xml',
    'MCP servers and scheduled tasks. Chat history is not included.',
    'MCP servers. Chat history is not included.')
sub('src/android/app/src/main/res/values-zh/strings.xml',
    'MCP 服务器和定时任务。不含聊天记录。',
    'MCP 服务器。不含聊天记录。')
sub('src/android/app/src/main/res/values-zh-rTW/strings.xml',
    'MCP 伺服器與定時任務。不含聊天記錄。',
    'MCP 伺服器。不含聊天記錄。')

print('ALL DONE')
