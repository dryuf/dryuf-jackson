package net.dryuf.jackson.serialize;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.mockito.Answers;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;


/**
 * Minimal JAX-RS runtime, so {@link Response} can be built without a full implementation on the classpath.
 */
public class StubRuntimeDelegate
{
	public static void install()
	{
		RuntimeDelegate delegate = mock(RuntimeDelegate.class, invocation ->
			invocation.getMethod().getName().equals("createResponseBuilder") ? responseBuilder() : null
		);
		RuntimeDelegate.setInstance(delegate);
	}

	private static Response.ResponseBuilder responseBuilder()
	{
		Object[] state = new Object[2];
		return mock(Response.ResponseBuilder.class, invocation -> {
			switch (invocation.getMethod().getName()) {
			case "status" -> {
				Object status = invocation.getArgument(0);
				state[0] = status instanceof Response.StatusType type ? type.getStatusCode() : status;
			}
			case "entity" -> state[1] = invocation.getArgument(0);
			case "build" -> {
				return response((Integer) state[0], state[1]);
			}
			}
			return invocation.getMock();
		});
	}

	private static Response response(int status, Object entity)
	{
		return mock(Response.class, withSettings().defaultAnswer(invocation -> switch (invocation.getMethod().getName()) {
			case "getStatus" -> status;
			case "getStatusInfo" -> Response.Status.fromStatusCode(status);
			case "getEntity" -> entity;
			case "hasEntity" -> entity != null;
			default -> Answers.RETURNS_DEFAULTS.answer(invocation);
		}));
	}
}
