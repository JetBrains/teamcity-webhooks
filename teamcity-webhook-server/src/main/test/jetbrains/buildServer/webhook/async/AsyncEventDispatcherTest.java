package jetbrains.buildServer.webhook.async;

import jetbrains.buildServer.serverSide.BuildServerListener;
import jetbrains.buildServer.serverSide.ServerPaths;
import jetbrains.buildServer.util.EventDispatcher;
import jetbrains.buildServer.util.ThreadUtil;
import jetbrains.buildServer.webhook.async.events.AsyncEvent;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class AsyncEventDispatcherTest {

    private AsyncEventDispatcher dispatcher;

    private EventDispatcher<BuildServerListener> serverEventDispatcher = EventDispatcher.create(BuildServerListener.class);
    @Mock
    private ServerPaths serverPaths;
    private OrderedExecutor executor;

    private final String mockPluginDataDirectory = "src/main/test/resources";
    private final Path unprocessedEventsFilePath = Paths.get(mockPluginDataDirectory + "/webhooks/unprocessedAsyncEvents.bak");

    @Before
    public void setUp() {
        executor = new OrderedExecutor();
        when(serverPaths.getPluginDataDirectory()).thenReturn(Paths.get(mockPluginDataDirectory).toFile());
        dispatcher = spy(new AsyncEventDispatcher(serverEventDispatcher, serverPaths, executor));
    }

    @Test
    public void testUnprocessedEventsAreSavedDuringServerShutdown() throws IOException, ClassNotFoundException {
        List<Long> processedEvents = new CopyOnWriteArrayList<>();
        dispatcher.subscribe("EVENT", event -> {
            ThreadUtil.sleep(100);
            processedEvents.add(event.getObjectId());
        });

        for (long i = 0; i < 20; i++)
            dispatcher.publish(new AsyncEvent("EVENT", i));

        serverEventDispatcher.getMulticaster().serverShutdown();

        while (!dispatcher.isTerminated()){ }

        FileInputStream fileInputStream = new FileInputStream(unprocessedEventsFilePath.toFile());
        ObjectInputStream objectInputStream = new ObjectInputStream(fileInputStream);

        List<AsyncEvent> queue = (List<AsyncEvent>) objectInputStream.readObject();

        for (int i = processedEvents.size(), j = 0; i < 20 ; i++, j++)
            assertEquals(Long.valueOf(i), queue.get(j).getObjectId());
    }

    @Test
    public void testUnprocessedEventsReplayDuringStartup() throws IOException {
        List<AsyncEvent> events = new CopyOnWriteArrayList<>();
        for (long i = 0; i < 20; i++)
            events.add(new AsyncEvent("EVENT", i));

        FileOutputStream fileOutputStream = new FileOutputStream(unprocessedEventsFilePath.toFile(), false);
        ObjectOutputStream objectOutputStream = new ObjectOutputStream(fileOutputStream);
        objectOutputStream.writeObject(events);

        List<AsyncEvent> result = new CopyOnWriteArrayList<>();
        AsyncEventListener listener = result::add;

        dispatcher.subscribe("EVENT", listener);

        serverEventDispatcher.getMulticaster().serverStartup();

        ThreadUtil.sleep(200);

        for (int i = 0; i < 20; i++)
            assertEquals(events.get(i).getObjectId(), result.get(i).getObjectId());
    }
}