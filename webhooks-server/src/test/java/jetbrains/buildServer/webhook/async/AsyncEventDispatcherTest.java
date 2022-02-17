package jetbrains.buildServer.webhook.async;

import jetbrains.buildServer.BaseTestCase;
import jetbrains.buildServer.messages.XStreamHolder;
import jetbrains.buildServer.serverSide.BuildServerListener;
import jetbrains.buildServer.serverSide.ServerPaths;
import jetbrains.buildServer.util.EventDispatcher;
import jetbrains.buildServer.util.ThreadUtil;
import jetbrains.buildServer.webhook.async.events.AsyncEvent;
import jetbrains.buildServer.xstream.XStreamFile;
import org.jetbrains.annotations.NotNull;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static jetbrains.buildServer.webhook.async.AsyncEventDispatcher.WEBHOOKS_UNPROCESSED_ASYNC_EVENTS_FILE;

@Test
public class AsyncEventDispatcherTest extends BaseTestCase {

    private final String EVENT_NAME = "EVENT";
    private AsyncEventDispatcher dispatcher;

    private EventDispatcher<BuildServerListener> serverEventDispatcher;

    private File unprocessedEventsFile;
    private final List<AsyncEvent> result = new CopyOnWriteArrayList<>();
    private final AsyncEventListener listener = createListener("testListener", result);

    @BeforeMethod
    public void setUp() throws IOException {
        serverEventDispatcher = EventDispatcher.create(BuildServerListener.class);
        File tempDir = createTempDir();
        ServerPaths serverPaths = new ServerPaths(tempDir);
        unprocessedEventsFile = new File(serverPaths.getPluginDataDirectory() + WEBHOOKS_UNPROCESSED_ASYNC_EVENTS_FILE);
        unprocessedEventsFile.mkdirs();
        unprocessedEventsFile.delete();
        Files.createFile(unprocessedEventsFile.toPath());
        dispatcher = new AsyncEventDispatcher(serverEventDispatcher, serverPaths);
        dispatcher.subscribe(EVENT_NAME, listener);
        result.clear();
    }

    @Test
    public void testUnprocessedEventsAreSavedDuringServerShutdown() {
        List<AsyncEvent> originalEvents = new ArrayList<>();
        for (long i = 0; i < 20; i++)
            originalEvents.add(new AsyncEvent(EVENT_NAME, new Random().nextLong()));

        originalEvents.forEach(dispatcher::publish);

        serverEventDispatcher.getMulticaster().serverShutdown();

        while (!dispatcher.isTerminated()){ }

        XStreamFile<List<AsyncEventDispatcher.TaskInfo>> file = new XStreamFile<>(unprocessedEventsFile, new XStreamHolder());
        List<AsyncEventDispatcher.TaskInfo> queue = file.deserialize().stream().filter( i -> i.getRunnerName().equals(listener.getUniqName())).collect(Collectors.toList());

        for (int i = result.size(), j = 0; i < 20 ; i++, j++) {
            assertEquals(originalEvents.get(i).getObjectId(), queue.get(j).getEvent().getObjectId());
        }
    }

    @Test
    public void testUnprocessedEventsReplayDuringStartup() {
        List<AsyncEventDispatcher.TaskInfo> events = new CopyOnWriteArrayList<>();
        for (long i = 0; i < 20; i++)
            events.add(new AsyncEventDispatcher.TaskInfo(listener.getUniqName(), new AsyncEvent(EVENT_NAME, new Random().nextLong())));

        XStreamFile<List<AsyncEventDispatcher.TaskInfo>> file = new XStreamFile<>(unprocessedEventsFile, new XStreamHolder());
        file.serialize(events, true);

        serverEventDispatcher.getMulticaster().serverStartup();

        ThreadUtil.sleep(500);

        assertEquals(events.size(), result.size());
        for (int i = 0; i < 20; i++) {
            assertEquals(events.get(i).getEvent().getObjectId(), result.get(i).getObjectId());
        }
    }

    @Test
    public void testUnprocessedEventsReplayForParticularListenerDuringStartup() {
        List<AsyncEvent> resultForSecondListener = new CopyOnWriteArrayList<>();
        AsyncEventListener secondListener = createListener("secondListener", resultForSecondListener);
        dispatcher.subscribe(EVENT_NAME, secondListener);

        List<AsyncEventDispatcher.TaskInfo> events = new CopyOnWriteArrayList<>();
        for (long i = 0; i < 20; i++) {
            events.add(new AsyncEventDispatcher.TaskInfo(listener.getUniqName(), new AsyncEvent(EVENT_NAME, new Random().nextLong())));
        }

        XStreamFile<List<AsyncEventDispatcher.TaskInfo>> file = new XStreamFile<>(unprocessedEventsFile, new XStreamHolder());
        file.serialize(events, true);

        serverEventDispatcher.getMulticaster().serverStartup();

        ThreadUtil.sleep(500);

        assertEquals(events.size(), result.size());
        assertEquals(0, resultForSecondListener.size());
        for (int i = 0; i < 20; i++) {
            assertEquals(events.get(i).getEvent().getObjectId(), result.get(i).getObjectId());
        }
    }

    @Test(expectedExceptions = IllegalStateException.class)
    public void testCannotSubscribeTwoListenersWithTheSameName() {
        dispatcher.subscribe(EVENT_NAME, new AsyncEventListener() {
            @Override
            public void handle(AsyncEvent event) {
                // do nothing
            }

            @NotNull
            @Override
            public String getUniqName() {
                return "testListener";
            }
        });
    }

    @NotNull
    private AsyncEventListener createListener(String name, List<AsyncEvent> result) {
        return new AsyncEventListener() {
            @Override
            public void handle(AsyncEvent event) {
                ThreadUtil.sleep(10);
                result.add(event);
            }

            @Override
            public String getUniqName() {
                return name;
            }
        };
    }
}