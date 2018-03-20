package org.thingsboard.rule.engine.filter;

import com.datastax.driver.core.utils.UUIDs;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Matchers;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;
import org.thingsboard.rule.engine.api.ListeningExecutor;
import org.thingsboard.rule.engine.api.TbContext;
import org.thingsboard.rule.engine.api.TbNodeConfiguration;
import org.thingsboard.rule.engine.api.TbNodeException;
import org.thingsboard.server.common.msg.TbMsg;
import org.thingsboard.server.common.msg.TbMsgMetaData;

import javax.script.ScriptException;
import java.util.concurrent.Callable;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class TbJsFilterNodeTest {

    private TbJsFilterNode node;

    @Mock
    private TbContext ctx;
    @Mock
    private ListeningExecutor executor;

    @Test
    public void falseEvaluationDoNotSendMsg() throws TbNodeException {
        initWithScript("10 > 15;");
        TbMsg msg = new TbMsg(UUIDs.timeBased(), "USER", null, new TbMsgMetaData(), "{}".getBytes());

        mockJsExecutor();

        node.onMsg(ctx, msg);
        verify(ctx).getJsExecutor();
        verifyNoMoreInteractions(ctx);
    }

    @Test
    public void notValidMsgDataThrowsException() throws TbNodeException {
        initWithScript("10 > 15;");
        TbMsg msg = new TbMsg(UUIDs.timeBased(), "USER", null, new TbMsgMetaData(), new byte[4]);

        when(ctx.getJsExecutor()).thenReturn(executor);

        mockJsExecutor();

        node.onMsg(ctx, msg);
        verifyError(msg, "Cannot bind js args", IllegalArgumentException.class);
    }

    @Test
    public void exceptionInJsThrowsException() throws TbNodeException {
        initWithScript("meta.temp.curr < 15;");
        TbMsgMetaData metaData = new TbMsgMetaData();
        TbMsg msg = new TbMsg(UUIDs.timeBased(), "USER", null, metaData, "{}".getBytes());
        mockJsExecutor();

        node.onMsg(ctx, msg);
        String expectedMessage = "TypeError: Cannot get property \"curr\" of null in <eval> at line number 1";
        verifyError(msg, expectedMessage, ScriptException.class);
    }

    @Test(expected = IllegalArgumentException.class)
    public void notValidScriptThrowsException() throws TbNodeException {
        initWithScript("10 > 15 asdq out");
    }

    @Test
    public void metadataConditionCanBeFalse() throws TbNodeException {
        initWithScript("meta.humidity < 15;");
        TbMsgMetaData metaData = new TbMsgMetaData();
        metaData.putValue("temp", "10");
        metaData.putValue("humidity", "99");
        TbMsg msg = new TbMsg(UUIDs.timeBased(), "USER", null, metaData, "{}".getBytes());
        mockJsExecutor();

        node.onMsg(ctx, msg);
        verify(ctx).getJsExecutor();
        verifyNoMoreInteractions(ctx);
    }

    @Test
    public void metadataConditionCanBeTrue() throws TbNodeException {
        initWithScript("meta.temp < 15;");
        TbMsgMetaData metaData = new TbMsgMetaData();
        metaData.putValue("temp", "10");
        metaData.putValue("humidity", "99");
        TbMsg msg = new TbMsg(UUIDs.timeBased(), "USER", null, metaData, "{}".getBytes());
        mockJsExecutor();

        node.onMsg(ctx, msg);
        verify(ctx).getJsExecutor();
        verify(ctx).tellNext(msg);
    }

    @Test
    public void msgJsonParsedAndBinded() throws TbNodeException {
        initWithScript("msg.passed < 15 && msg.name === 'Vit' && meta.temp == 10 && msg.bigObj.prop == 42;");
        TbMsgMetaData metaData = new TbMsgMetaData();
        metaData.putValue("temp", "10");
        metaData.putValue("humidity", "99");
        String rawJson = "{\"name\": \"Vit\", \"passed\": 5, \"bigObj\": {\"prop\":42}}";

        TbMsg msg = new TbMsg(UUIDs.timeBased(), "USER", null, metaData, rawJson.getBytes());
        mockJsExecutor();

        node.onMsg(ctx, msg);
        verify(ctx).getJsExecutor();
        verify(ctx).tellNext(msg);
    }

    private void initWithScript(String script) throws TbNodeException {
        TbJsFilterNodeConfiguration config = new TbJsFilterNodeConfiguration();
        config.setJsScript(script);
        ObjectMapper mapper = new ObjectMapper();
        TbNodeConfiguration nodeConfiguration = new TbNodeConfiguration();
        nodeConfiguration.setData(mapper.valueToTree(config));

        node = new TbJsFilterNode();
        node.init(nodeConfiguration, null);
    }

    private void mockJsExecutor() {
        when(ctx.getJsExecutor()).thenReturn(executor);
        doAnswer((Answer<ListenableFuture<Boolean>>) invocationOnMock -> {
            try {
                Callable task = (Callable) (invocationOnMock.getArguments())[0];
                return Futures.immediateFuture((Boolean) task.call());
            } catch (Throwable th) {
                return Futures.immediateFailedFuture(th);
            }
        }).when(executor).executeAsync(Matchers.any(Callable.class));
    }

    private void verifyError(TbMsg msg, String message, Class expectedClass) {
        ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
        verify(ctx).tellError(same(msg), captor.capture());

        Throwable value = captor.getValue();
        assertEquals(expectedClass, value.getClass());
        assertEquals(message, value.getMessage());
    }
}