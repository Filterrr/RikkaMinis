# -*- coding: utf-8 -*-
import sys, io

def sub(path, old, new, expect=1):
    with io.open(path, encoding='utf-8') as f:
        s = f.read()
    n = s.count(old)
    if n != expect:
        print("FAIL %s: expected %d found %d for:\n%r" % (path, expect, n, old[:150]))
        sys.exit(1)
    s = s.replace(old, new)
    with io.open(path, 'w', encoding='utf-8') as f:
        f.write(s)
    print("OK  %s: replaced %d" % (path, n))

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
    print("OK  %s: dropped %d lines" % (path, removed))

R = "src/android/app/src/main/java/com/openminis/app/"

# ── ChatViewModel.kt: 系统提示词里的两段文档 ──
drop_lines(R + "ui/chat/ChatViewModel.kt",
           lambda l: l.startswith("- minis-scheduled: "), 1)
sub(R + "ui/chat/ChatViewModel.kt", """
Scheduled tasks: crontab / at / nohup loops will stop when the app is suspended, so in-app scheduled scripts may not run as expected. For recurring tasks that must fire while the app is backgrounded, use the native alarm tool (AlarmManager) or tell the user to set up a system-level schedule (Google Calendar event, Tasker automation, etc.). (Waiting or polling WITHIN the current turn is different — that is what shell_execute `delay` chains are for, per the shell_execute notes above.)""", "")

# ── AppNavigation.kt ──
sub(R + "ui/navigation/AppNavigation.kt", """
    /** [T-android-scheduled-tasks-design] Scheduled tasks list + editor. */
    const val SCHEDULED_TASKS = "scheduled_tasks"
    const val SCHEDULED_TASK_EDIT = "scheduled_tasks/edit?taskId={taskId}"
    fun scheduledTaskEdit(taskId: String? = null): String =
        if (taskId == null) "scheduled_tasks/edit" else "scheduled_tasks/edit?taskId=$taskId"
    // [T-android-scheduled-tasks-run-records] per-task execution log.
    const val SCHEDULED_TASK_RUNS = "scheduled_tasks/runs/{taskId}"
    fun scheduledTaskRuns(taskId: String): String = "scheduled_tasks/runs/$taskId"
""", "")
sub(R + "ui/navigation/AppNavigation.kt", """
                onScheduledTasksClick = {
                    navController.safeNavigate(Routes.SCHEDULED_TASKS)
                },""", "")
sub(R + "ui/navigation/AppNavigation.kt", """
        // [T-android-scheduled-tasks-design] Scheduled tasks list + editor.
        composable(Routes.SCHEDULED_TASKS) {
            com.openminis.app.ui.scheduled.ScheduledTasksScreen(
                onBack = { navController.safePopBackStack() },
                onEditTask = { taskId ->
                    navController.safeNavigate(Routes.scheduledTaskEdit(taskId))
                },
                onViewRuns = { taskId ->
                    navController.safeNavigate(Routes.scheduledTaskRuns(taskId))
                },
                onOpenSession = { sessionId ->
                    navController.safeNavigate(Routes.chat(sessionId))
                },
            )
        }
        // [T-android-scheduled-tasks-run-records] per-task run records.
        composable(
            route = Routes.SCHEDULED_TASK_RUNS,
            arguments = listOf(navArgument("taskId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val taskId = backStackEntry.arguments?.getString("taskId") ?: return@composable
            com.openminis.app.ui.scheduled.ScheduledTaskRunsScreen(
                taskId = taskId,
                onBack = { navController.safePopBackStack() },
                onOpenSession = { sessionId ->
                    navController.safeNavigate(Routes.chat(sessionId))
                },
            )
        }
        composable(
            route = Routes.SCHEDULED_TASK_EDIT,
            arguments = listOf(
                navArgument("taskId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val taskId = backStackEntry.arguments?.getString("taskId")
            com.openminis.app.ui.scheduled.ScheduledTaskEditScreen(
                taskId = taskId,
                onBack = { navController.safePopBackStack() },
                onOpenSession = { sessionId ->
                    navController.safeNavigate(Routes.chat(sessionId))
                },
            )
        }""", "")

# ── SessionListScreen.kt ──
sub(R + "ui/sessions/SessionListScreen.kt", """
    // [T-android-scheduled-tasks-design] Entry to the scheduled-tasks list.
    onScheduledTasksClick: () -> Unit = {},""", "")
sub(R + "ui/sessions/SessionListScreen.kt", """
    // [T-android-scheduled-tasks-full] Live count of scheduled tasks for the
    // toolbar clock-icon badge. Observes the SharedPreferences-backed store so
    // the badge updates when tasks are added / removed without a manual refresh.
    // [T-android-scheduled-badge-enabled-only] Count only enabled tasks so the
    // badge reflects what's actually active — disabled tasks don't contribute,
    // and with none enabled the count is 0 (badge hidden by the >0 gate below).
    val scheduledTaskCount by remember {
        com.openminis.app.scheduled.ScheduledTaskStore(context).observe()
            .map { list -> list.count { it.enabled } }
    }.collectAsState(initial = 0)
""", "")
sub(R + "ui/sessions/SessionListScreen.kt", """
                        // [T-android-scheduled-tasks-design] Scheduled-tasks entry,
                        // sits to the left of the Shell button on the home toolbar.
                        // [T-android-scheduled-tasks-full] Badge shows the count of
                        // scheduled tasks so the user can see at a glance how many
                        // are configured without opening the list.
                        IconButton(onClick = onScheduledTasksClick) {
                            if (scheduledTaskCount > 0) {
                                BadgedBox(badge = { Badge { Text("$scheduledTaskCount") } }) {
                                    Icon(
                                        Icons.Outlined.Schedule,
                                        contentDescription = stringResource(R.string.sessionlist_scheduled_tasks),
                                    )
                                }
                            } else {
                                Icon(
                                    Icons.Outlined.Schedule,
                                    contentDescription = stringResource(R.string.sessionlist_scheduled_tasks),
                                )
                            }
                        }""", "")

# ── BackupSettingsScreen.kt ──
sub(R + "ui/settings/BackupSettingsScreen.kt",
    "import com.openminis.app.scheduled.ScheduledTaskManager\n", "")
sub(R + "ui/settings/BackupSettingsScreen.kt", """
    // ScheduledTaskManager is a stateless wrapper over its own store +
    // AlarmManager (every other call site constructs one on demand), so it is
    // built here rather than threaded through the navigation graph. Restoring
    // through the manager — not the raw store — is what actually re-arms the
    // alarms; writing rows alone would leave tasks that are visible but dead.
    val scheduledManager = remember(context) { ScheduledTaskManager(context) }
""", "")
sub(R + "ui/settings/BackupSettingsScreen.kt", """
                scheduledManager = scheduledManager,""", "")
sub(R + "ui/settings/BackupSettingsScreen.kt", """
                    scheduledStore = scheduledManager.store(),""", "")
sub(R + "ui/settings/BackupSettingsScreen.kt", """
                    if (report.scheduledTasksImported > 0) {
                        Text(
                            stringResource(
                                R.string.backup_done_scheduled_tasks,
                                report.scheduledTasksImported,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }""", "")

# ── ConfigBackup.kt ──
sub(R + "backup/ConfigBackup.kt",
    "import com.openminis.app.scheduled.ScheduledTask\n"
    "import com.openminis.app.scheduled.ScheduledTaskManager\n"
    "import com.openminis.app.scheduled.ScheduledTaskStore\n", "")
sub(R + "backup/ConfigBackup.kt", """
        /** Scheduled tasks restored and re-armed. */
        val scheduledTasksImported: Int,""", "")
sub(R + "backup/ConfigBackup.kt", """
        scheduledStore: ScheduledTaskStore? = null,
    ): String {""", """
    ): String {""")
sub(R + "backup/ConfigBackup.kt", """
        // Scheduled tasks (SharedPreferences-backed). Run history is dropped on
        // purpose: it points at session ids that will not exist on the target
        // install, and a restored task's job is to fire in future, not to carry
        // a log of past firings.
        val scheduledTasks = JSONArray()
        if (scheduledStore != null) {
            for (task in runCatching { scheduledStore.all() }.getOrDefault(emptyList())) {
                val obj = runCatching { task.toJson() }.getOrNull() ?: continue
                obj.remove("runs")
                obj.remove("lastFiredAt")
                obj.remove("lastResultPreview")
                obj.remove("lastResultSessionId")
                scheduledTasks.put(obj)
            }
        }

        return JSONObject().apply {""", """
        return JSONObject().apply {""")
sub(R + "backup/ConfigBackup.kt", """
            put("scheduledTasks", scheduledTasks)""", "")
sub(R + "backup/ConfigBackup.kt", """
        scheduledStore: ScheduledTaskStore? = null,
        scheduledManager: ScheduledTaskManager? = null,
    ): ImportResult {""", """
    ): ImportResult {""")
sub(R + "backup/ConfigBackup.kt", """
        // -- Stage 8: scheduled tasks (restored AND re-armed with AlarmManager) --
        // Writing the rows without registering alarms would produce tasks that
        // are visible in the list but never fire, which is worse than not
        // restoring them at all.
        var scheduledTasksImported = 0
        val schedArr = root.optJSONArray("scheduledTasks")
        val effectiveStore = scheduledStore ?: scheduledManager?.store()
        if (schedArr != null && effectiveStore != null) {
            val existingIds = runCatching { effectiveStore.all().map { it.id }.toSet() }
                .getOrDefault(emptySet())
            for (i in 0 until schedArr.length()) {
                val t = schedArr.optJSONObject(i) ?: continue
                val label = t.optString("label", "task #${i + 1}")
                try {
                    val task = ScheduledTask.fromJson(t)
                    if (task.id in existingIds) {
                        skipped.add("scheduled task \"$label\": already exists, left as-is")
                        continue
                    }
                    if (scheduledManager != null) {
                        scheduledManager.create(task)
                    } else {
                        effectiveStore.upsert(task)
                    }
                    scheduledTasksImported++
                } catch (t2: Throwable) {
                    skipped.add("scheduled task \"$label\": ${t2.message ?: "import failed"}")
                }
            }
            if (scheduledTasksImported > 0 && scheduledManager == null) {
                skipped.add(
                    "$scheduledTasksImported scheduled task(s) restored but not re-armed — " +
                        "reopen the app to schedule them"
                )
            }
        } else if (schedArr != null && schedArr.length() > 0 && effectiveStore == null) {
            skipped.add("${schedArr.length()} scheduled task(s): not restorable here")
        }

        return ImportResult(""", """
        return ImportResult(""")
sub(R + "backup/ConfigBackup.kt", """
            scheduledTasksImported = scheduledTasksImported,""", "")
sub(R + "backup/ConfigBackup.kt",
    '"mcpServers=$mcpServersImported scheduledTasks=$scheduledTasksImported " +',
    '"mcpServers=$mcpServersImported " +')

# ── AndroidManifest.xml ──
sub("src/android/app/src/main/AndroidManifest.xml", """
        <!-- [T-android-scheduled-tasks-design] AlarmManager target for
             user-defined scheduled tasks (Settings → home top-right
             schedule icon). The receiver dispatches to ScheduledAgentRunner
             which drives the agent loop headlessly. -->
        <receiver
            android:name=".scheduled.ScheduledTaskAlarmReceiver"
            android:exported="false" />

""", "")

# ── strings.xml (4 个语言文件) ──
for lang in ["values", "values-zh", "values-zh-rTW", "values-ru"]:
    p = "src/android/app/src/main/res/%s/strings.xml" % lang
    drop_lines(p,
               lambda l: (l.strip().startswith('<string name="scheduled_')
                          or l.strip().startswith('<string name="sessionlist_scheduled_tasks">')
                          or l.strip().startswith('<string name="backup_done_scheduled_tasks">')
                          or l.strip().startswith('<!-- [T-android-scheduled-tasks')),
               1)

sub("src/android/app/src/main/res/values/strings.xml",
    "MCP servers and scheduled tasks. Chat history",
    "MCP servers. Chat history")
sub("src/android/app/src/main/res/values-zh/strings.xml",
    "MCP 服务器和定时任务。不含聊天记录",
    "MCP 服务器。不含聊天记录")
sub("src/android/app/src/main/res/values-zh-rTW/strings.xml",
    "MCP 伺服器與定時任務。不含聊天記錄",
    "MCP 伺服器。不含聊天記錄")

# ── ConfigBackupPayloadTest.kt ──
T = "src/android/app/src/test/java/com/openminis/app/backup/ConfigBackupPayloadTest.kt"
sub(T, " * servers and scheduled tasks.", " * servers.")
sub(T, "// Backups written before skills/memory/MCP/scheduled were covered have",
    "// Backups written before skills/memory/MCP were covered have")
sub(T, '        assertNull(old.optJSONArray("scheduledTasks"))\n', "")
sub(T, """
    @Test
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

    @Test
    fun `mcp server entry keeps the shape importJSON reads`() {""", """
    @Test
    fun `mcp server entry keeps the shape importJSON reads`() {""")

print("ALL DONE")
