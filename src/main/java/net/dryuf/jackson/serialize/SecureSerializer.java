package net.dryuf.jackson.serialize;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Default;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import net.dryuf.base.security.annotation.SecureIgnoreNull;
import net.dryuf.base.security.annotation.SecureWrite;
import net.dryuf.base.security.identity.IdentityContext;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.introspect.AnnotatedMember;
import tools.jackson.databind.introspect.BeanPropertyDefinition;
import tools.jackson.databind.node.MissingNode;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.stream.Stream;


/**
 * Applies JSON input to a bean, enforcing {@link SecureWriteField} restrictions against the passed
 * {@link IdentityContext}.
 * <p>
 * Properties are discovered by Jackson bean introspection (public getters / setters, {@code @JsonProperty} names,
 * {@code @JsonIgnore}, ...), the values are written by Jackson as well, so the behavior matches regular
 * deserialization.  A property is writable when Jackson has a mutator for it (setter / visible field) and the caller
 * has one of {@link SecureWriteField#rolesAllowed()}.
 * <p>
 * A restricted property present in the input is rejected (403) unless its value equals the current value, so clients
 * can send back what they received from GET (e.g. admin echoing {@code views}).
 * <p>
 * The identity is passed explicitly (not injected), so the writer does not depend on an active request context and can
 * be called from any thread.
 */
@Default
@ApplicationScoped
@RequiredArgsConstructor(onConstructor_ = {@Inject})
public class SecureSerializer
{
	public enum Mode
	{
		/**
		 * New object: absent properties keep their defaults.
		 */
		CREATE,
		/**
		 * PUT: absent properties writable by caller are reset to null/0, restricted ones are preserved.
		 */
		REPLACE,
		/**
		 * PATCH (JSON merge patch): only present properties are modified.
		 */
		MERGE,
	}

	private final ObjectMapper objectMapper;

	private final Map<Class<?>, Map<String, PropertyAccess>> propertiesCache = new ConcurrentHashMap<>();

	public <T> T deserialize(T target, JsonNode input, Mode mode, IdentityContext identity)
	{
		Map<String, PropertyAccess> properties = propertiesCache.computeIfAbsent(target.getClass(), this::introspect);

		ObjectNode accepted = objectMapper.createObjectNode();
		List<String> denied = new ArrayList<>();
		for (Map.Entry<String, JsonNode> entry : input.properties()) {
			String key = entry.getKey();
			PropertyAccess property = properties.get(key);
			if (property == null) {
				throw error(BadRequestException::new, Response.Status.BAD_REQUEST,
					"Unknown field: "+entry.getKey());
			}
			final JsonNode value = entry.getValue();
			if (value.isNull() && property.ignoreNull) {
				// ignore
			}
			else if (property.canWrite(identity)) {
				accepted.set(key, value);
			}
			else if (!sameValue(property.read(target, objectMapper), value)) {
				denied.add(key);
			}
		}
		if (!denied.isEmpty()) {
			throw error(ForbiddenException::new, Response.Status.FORBIDDEN,
				"Not allowed to modify fields "+denied+" with "+
					"roles "+identity.getRoles());
		}
		if (mode == Mode.REPLACE) {
			properties.forEach((name, property) -> {
				if (!input.has(name) && property.canWrite(identity)) {
					// do NOT do this, keep original property even for REPLACE - we do not know
					// the default value
					//accepted.set(name, NullNode.getInstance());
				}
			});
		}
		try {
			return objectMapper.readerForUpdating(target).readValue(accepted);
		}
		catch (JacksonException ex) {
			throw error(BadRequestException::new, Response.Status.BAD_REQUEST,
				"Invalid input: "+ex.getMessage());
		}
	}

	public static WebApplicationException error(BiFunction<String, Response, WebApplicationException> clazz, Response.Status status,
	                                            String message)
	{
		return clazz.apply(message, Response.status(status)
			.type(MediaType.APPLICATION_JSON_TYPE)
			.entity(Map.of("status", status.getStatusCode(), "error", message))
			.build());
	}

	private Map<String, PropertyAccess> introspect(Class<?> clazz)
	{
		BeanDescription description = objectMapper._deserializationContext()
			.introspectBeanDescription(objectMapper.constructType(clazz));
		Map<String, PropertyAccess> result = new LinkedHashMap<>();
		for (BeanPropertyDefinition definition : description.findProperties()) {
			AnnotatedMember accessor = definition.getAccessor();
			if (accessor != null) {
				accessor.fixAccess(true);
			}
			SecureWrite secureWrite = Stream.of(definition.getSetter(), definition.getField(), definition.getGetter())
				.filter(member -> member != null)
				.map(member -> member.getAnnotation(SecureWrite.class))
				.filter(a -> a != null)
				.findFirst()
				.orElse(null);
			SecureIgnoreNull secureIgnoreNull = Stream.of(definition.getSetter(), definition.getField(),
					definition.getGetter())
				.filter(member -> member != null)
				.map(member -> member.getAnnotation(SecureIgnoreNull.class))
				.filter(a -> a != null)
				.findFirst()
				.orElse(null);
			result.put(definition.getName(), new PropertyAccess(
				accessor,
				definition.getMutator() != null,
				secureWrite == null ? null : secureWrite.rolesAllowed(),
				secureIgnoreNull == null ? false : secureIgnoreNull.value()
			));
		}
		return Map.copyOf(result);
	}

	private static boolean sameValue(JsonNode current, JsonNode input)
	{
		if (current.isNumber() && input.isNumber()) {
			return current.decimalValue().compareTo(input.decimalValue()) == 0;
		}
		return current.equals(input);
	}

	/**
	 * Write access of single bean property.
	 *
	 * @param accessor
	 * 	getter or field to read current value, null if write-only
	 * @param mutable
	 * 	whether Jackson can set the property
	 * @param rolesAllowed
	 * 	roles allowed to write, null if unrestricted
	 */
	private record PropertyAccess(AnnotatedMember accessor, boolean mutable, String[] rolesAllowed, boolean ignoreNull)
	{
		boolean canWrite(IdentityContext identity)
		{
			return mutable && (rolesAllowed == null || Arrays.stream(rolesAllowed).anyMatch(identity::hasRole));
		}

		JsonNode read(Object target, ObjectMapper objectMapper)
		{
			if (accessor == null) {
				return MissingNode.getInstance();
			}
			JsonNode value = objectMapper.valueToTree(accessor.getValue(target));
			return value == null ? NullNode.getInstance() : value;
		}
	}
}
