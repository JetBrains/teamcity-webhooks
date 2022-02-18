package jetbrains.buildServer.webhook.async;

import jetbrains.buildServer.messages.XStreamHolder;
import jetbrains.buildServer.serverSide.BuildServerListener;
import jetbrains.buildServer.serverSide.ServerPaths;
import jetbrains.buildServer.util.EventDispatcher;
import jetbrains.buildServer.util.ThreadUtil;
import jetbrains.buildServer.webhook.async.events.AsyncEvent;
import jetbrains.buildServer.xstream.XStreamFile;
import org.jetbrains.annotations.NotNull;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class AsyncEventDispatcherTest {

    private final String EVENT_NAME = "EVENT";
    private AsyncEventDispatcher dispatcher;

    private EventDispatcher<BuildServerListener> serverEventDispatcher = EventDispatcher.create(BuildServerListener.class);
    @Mock
    private ServerPaths serverPaths;

    private final String mockPluginDataDirectory = "src/main/test/resources";
    private final Path unprocessedEventsFilePath = Paths.get(mockPluginDataDirectory + "/webhooks/unprocessedAsyncEvents.bak");
    private final List<AsyncEvent> result = new CopyOnWriteArrayList<>();
    private final AsyncEventListener listener = createListener("testListener", result);

    @Before
    public void setUp() {
        when(serverPaths.getPluginDataDirectory()).thenReturn(Paths.get(mockPluginDataDirectory).toFile());
        dispatcher = spy(new AsyncEventDispatcher(serverEventDispatcher, serverPaths));
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

        XStreamFile<List<AsyncEventDispatcher.TaskInfo>> file = new XStreamFile<>(unprocessedEventsFilePath.toFile(), new XStreamHolder());
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

        XStreamFile<List<AsyncEventDispatcher.TaskInfo>> file = new XStreamFile<>(unprocessedEventsFilePath.toFile(), new XStreamHolder());
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

        XStreamFile<List<AsyncEventDispatcher.TaskInfo>> file = new XStreamFile<>(unprocessedEventsFilePath.toFile(), new XStreamHolder());
        file.serialize(events, true);

        serverEventDispatcher.getMulticaster().serverStartup();

        ThreadUtil.sleep(500);

        assertEquals(events.size(), result.size());
        assertEquals(0, resultForSecondListener.size());
        for (int i = 0; i < 20; i++) {
            assertEquals(events.get(i).getEvent().getObjectId(), result.get(i).getObjectId());
        }
    }

    @Test(expected = IllegalStateException.class)
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