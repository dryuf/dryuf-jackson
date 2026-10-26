package net.dryuf.jackson.serialize;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.WebApplicationException;
import lombok.Getter;
import lombok.Setter;
import net.dryuf.base.security.annotation.SecureIgnoreNull;
import net.dryuf.base.security.annotation.SecureWrite;
import net.dryuf.base.security.identity.FixedIdentityContext;
import net.dryuf.base.security.identity.IdentityContext;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;


public class SecureSerializerTest
{
	private static final IdentityContext ANONYMOUS = identity();
	private static final IdentityContext EDITOR = identity("editor");
	private static final IdentityContext ADMIN = identity("admin");

	private final ObjectMapper objectMapper = JsonMapper.builder().build();

	private final SecureSerializer writer = new SecureSerializer(objectMapper);

	@BeforeClass
	public void setUp()
	{
		StubRuntimeDelegate.install();
	}

	@Test
	public void deserialize_unrestricted_written()
	{
		Article article = article();

		Article result = writer.deserialize(article, json("{'title':'New'}"), SecureSerializer.Mode.MERGE, ANONYMOUS);

		assertSame(result, article);
		assertEquals(article.getTitle(), "New");
	}

	@Test
	public void deserialize_jsonPropertyName_written()
	{
		Article article = article();

		writer.deserialize(article, json("{'body':'Text'}"), SecureSerializer.Mode.MERGE, ANONYMOUS);

		assertEquals(article.getContent(), "Text");
	}

	@Test
	public void deserialize_javaNameOfRenamed_badRequest()
	{
		WebApplicationException ex = expectThrows(BadRequestException.class, () ->
			writer.deserialize(article(), json("{'content':'Text'}"), SecureSerializer.Mode.MERGE, ANONYMOUS));

		assertError(ex, 400, "Unknown field: content");
	}

	@Test
	public void deserialize_unknownField_badRequest()
	{
		WebApplicationException ex = expectThrows(BadRequestException.class, () ->
			writer.deserialize(article(), json("{'unknown':1}"), SecureSerializer.Mode.MERGE, ADMIN));

		assertError(ex, 400, "Unknown field: unknown");
	}

	@Test
	public void deserialize_jsonIgnored_badRequest()
	{
		Article article = article();

		expectThrows(BadRequestException.class, () ->
			writer.deserialize(article, json("{'secret':'x'}"), SecureSerializer.Mode.MERGE, ADMIN));
		assertEquals(article.getSecret(), "hidden");
	}

	@Test
	public void deserialize_restrictedWithRole_written()
	{
		Article article = article();

		writer.deserialize(article, json("{'views':99,'tags':['x']}"), SecureSerializer.Mode.MERGE, ADMIN);

		assertEquals(article.getViews(), 99);
		assertEquals(article.getTags(), List.of("x"));
	}

	@Test
	public void deserialize_restrictedWithoutRole_forbidden()
	{
		Article article = article();

		WebApplicationException ex = expectThrows(ForbiddenException.class, () ->
			writer.deserialize(article, json("{'title':'New','views':99,'tags':['x']}"), SecureSerializer.Mode.MERGE, EDITOR));

		assertError(ex, 403, "Not allowed to modify fields [views, tags] with roles [editor]");
		assertEquals(article.getTitle(), "Old");
		assertEquals(article.getViews(), 10);
	}

	@Test
	public void deserialize_restrictedSameValue_accepted()
	{
		Article article = article();

		writer.deserialize(article, json("{'title':'New','views':10,'tags':['a','b']}"), SecureSerializer.Mode.MERGE, EDITOR);

		assertEquals(article.getTitle(), "New");
		assertEquals(article.getViews(), 10);
		assertEquals(article.getTags(), List.of("a", "b"));
	}

	@Test
	public void deserialize_restrictedSameNumberDifferentScale_accepted()
	{
		Article article = article();

		writer.deserialize(article, json("{'views':10.0}"), SecureSerializer.Mode.MERGE, EDITOR);

		assertEquals(article.getViews(), 10);
	}

	@Test
	public void deserialize_restrictedSameNull_accepted()
	{
		Article article = article();
		article.setTags(null);

		writer.deserialize(article, json("{'tags':null}"), SecureSerializer.Mode.MERGE, EDITOR);

		assertNull(article.getTags());
	}

	@Test
	public void deserialize_restrictedDifferentOrder_forbidden()
	{
		expectThrows(ForbiddenException.class, () ->
			writer.deserialize(article(), json("{'tags':['b','a']}"), SecureSerializer.Mode.MERGE, EDITOR));
	}

	@Test
	public void deserialize_emptyRolesAllowed_readOnlyForAll()
	{
		Article article = article();

		WebApplicationException ex = expectThrows(ForbiddenException.class, () ->
			writer.deserialize(article, json("{'id':2}"), SecureSerializer.Mode.MERGE, ADMIN));

		assertError(ex, 403, "Not allowed to modify fields [id] with roles [admin]");
		assertEquals(article.getId(), Long.valueOf(1));
	}

	@Test
	public void deserialize_emptyRolesAllowedSameValue_accepted()
	{
		Article article = article();

		writer.deserialize(article, json("{'id':1}"), SecureSerializer.Mode.MERGE, ADMIN);

		assertEquals(article.getId(), Long.valueOf(1));
	}

	@Test
	public void deserialize_annotatedSetter_anyListedRole()
	{
		Article byEditor = article();
		Article byAdmin = article();

		writer.deserialize(byEditor, json("{'status':'published'}"), SecureSerializer.Mode.MERGE, EDITOR);
		writer.deserialize(byAdmin, json("{'status':'archived'}"), SecureSerializer.Mode.MERGE, ADMIN);

		assertEquals(byEditor.getStatus(), "published");
		assertEquals(byAdmin.getStatus(), "archived");
		expectThrows(ForbiddenException.class, () ->
			writer.deserialize(article(), json("{'status':'published'}"), SecureSerializer.Mode.MERGE, ANONYMOUS));
	}

	@Test
	public void deserialize_annotatedGetter_restricted()
	{
		Article article = article();

		writer.deserialize(article, json("{'owner':'bob'}"), SecureSerializer.Mode.MERGE, ADMIN);

		assertEquals(article.getOwner(), "bob");
		expectThrows(ForbiddenException.class, () ->
			writer.deserialize(article(), json("{'owner':'bob'}"), SecureSerializer.Mode.MERGE, EDITOR));
	}

	@Test
	public void deserialize_setterAndFieldAnnotated_setterWins()
	{
		Article article = article();

		writer.deserialize(article, json("{'category':'news'}"), SecureSerializer.Mode.MERGE, EDITOR);

		assertEquals(article.getCategory(), "news");
		expectThrows(ForbiddenException.class, () ->
			writer.deserialize(article(), json("{'category':'news'}"), SecureSerializer.Mode.MERGE, ADMIN));
	}

	@Test
	public void deserialize_readOnlyComputedDifferent_forbidden()
	{
		expectThrows(ForbiddenException.class, () ->
			writer.deserialize(article(), json("{'summary':'other'}"), SecureSerializer.Mode.MERGE, ADMIN));
	}

	@Test
	public void deserialize_readOnlyComputedSame_ignored()
	{
		Article article = article();

		writer.deserialize(article, json("{'summary':'Old/10'}"), SecureSerializer.Mode.MERGE, ADMIN);

		assertEquals(article.getSummary(), "Old/10");
	}

	@Test
	public void deserialize_writeOnlyUnrestricted_written()
	{
		Article article = article();

		writer.deserialize(article, json("{'password':'pwd'}"), SecureSerializer.Mode.MERGE, ANONYMOUS);

		assertEquals(article.password, "pwd");
	}

	@Test
	public void deserialize_writeOnlyRestrictedWithoutRole_forbidden()
	{
		expectThrows(ForbiddenException.class, () ->
			writer.deserialize(article(), json("{'apiKey':'key'}"), SecureSerializer.Mode.MERGE, EDITOR));
	}

	@Test
	public void deserialize_invalidValue_badRequest()
	{
		WebApplicationException ex = expectThrows(BadRequestException.class, () ->
			writer.deserialize(article(), json("{'views':'many'}"), SecureSerializer.Mode.MERGE, ADMIN));

		assertEquals(ex.getResponse().getStatus(), 400);
		assertTrue(ex.getMessage().startsWith("Invalid input: "), ex.getMessage());
	}

	@Test
	public void deserialize_merge_absentPreserved()
	{
		Article article = article();

		writer.deserialize(article, json("{'title':'New'}"), SecureSerializer.Mode.MERGE, ADMIN);

		assertEquals(article.getContent(), "Body");
		assertEquals(article.getStatus(), "draft");
		assertEquals(article.getViews(), 10);
	}

	@Test
	public void deserialize_create_absentPreserved()
	{
		Article article = new Article();

		writer.deserialize(article, json("{'title':'New'}"), SecureSerializer.Mode.CREATE, ADMIN);

		assertEquals(article.getTitle(), "New");
		assertEquals(article.getStatus(), "draft");
	}

	@Test
	public void deserialize_mergeExplicitNull_cleared()
	{
		Article article = article();

		writer.deserialize(article, json("{'body':null}"), SecureSerializer.Mode.MERGE, ANONYMOUS);

		assertNull(article.getContent());
	}

	@Test
	public void deserialize_mergeExplicitNullIgnore_kept()
	{
		Article article = article();

		writer.deserialize(article, json("{'alwaysValid':null}"), SecureSerializer.Mode.MERGE, ADMIN);

		assertEquals(article.getAlwaysValid(), "hello");
	}

	@Test
	public void deserialize_replace_absentWritableKeep()
	{
		Article article = article();

		writer.deserialize(article, json("{'title':'New'}"), SecureSerializer.Mode.REPLACE, EDITOR);

		assertEquals(article.getTitle(), "New");
		assertNotNull(article.getContent());
		assertNotNull(article.getStatus());
		assertNotNull(article.getCategory());
		assertNotNull(article.password);
	}

	@Test
	public void deserialize_replace_absentRestrictedPreserved()
	{
		Article article = article();

		writer.deserialize(article, json("{'title':'New'}"), SecureSerializer.Mode.REPLACE, EDITOR);

		assertEquals(article.getId(), Long.valueOf(1));
		assertEquals(article.getViews(), 10);
		assertEquals(article.getTags(), List.of("a", "b"));
		assertEquals(article.getOwner(), "alice");
		assertEquals(article.apiKey, "key");
		assertEquals(article.getSecret(), "hidden");
	}

	@Test
	public void deserialize_replaceAbsentPrimitive_keep()
	{
		Article article = article();

		writer.deserialize(article, json("{}"), SecureSerializer.Mode.REPLACE, ADMIN);

		assertEquals(article.getViews(), 10);
		assertNotNull(article.getTags());
	}

	@Test
	public void deserialize_repeated_sameResult()
	{
		Article first = article();
		Article second = article();

		writer.deserialize(first, json("{'title':'One'}"), SecureSerializer.Mode.MERGE, ANONYMOUS);
		writer.deserialize(second, json("{'title':'Two'}"), SecureSerializer.Mode.MERGE, ANONYMOUS);

		assertEquals(first.getTitle(), "One");
		assertEquals(second.getTitle(), "Two");
	}

	@Test
	public void deserialize_differentClasses_introspectedSeparately()
	{
		Comment comment = new Comment();

		writer.deserialize(comment, json("{'text':'Hi'}"), SecureSerializer.Mode.MERGE, ANONYMOUS);

		assertEquals(comment.getText(), "Hi");
		expectThrows(BadRequestException.class, () ->
			writer.deserialize(comment, json("{'title':'x'}"), SecureSerializer.Mode.MERGE, ANONYMOUS));
	}

	@Test
	public void error_built_statusAndEntity()
	{
		WebApplicationException ex = SecureSerializer.error(ForbiddenException::new,
			jakarta.ws.rs.core.Response.Status.FORBIDDEN, "Nope");

		assertTrue(ex instanceof ForbiddenException);
		assertError(ex, 403, "Nope");
	}

	private static void assertError(WebApplicationException ex, int status, String message)
	{
		assertEquals(ex.getMessage(), message);
		assertEquals(ex.getResponse().getStatus(), status);
		assertEquals(ex.getResponse().getEntity(), Map.of("status", status, "error", message));
	}

	private ObjectNode json(String content)
	{
		return (ObjectNode) objectMapper.readTree(content.replace('\'', '"'));
	}

	private static IdentityContext identity(String... roles)
	{
		return new FixedIdentityContext(null, Set.of(roles), roles.length == 0);
	}

	private static Article article()
	{
		Article article = new Article();
		article.id = 1L;
		article.setTitle("Old");
		article.setContent("Body");
		article.setViews(10);
		article.setAlwaysValid("hello");
		article.setTags(List.of("a", "b"));
		article.setOwner("alice");
		article.setCategory("misc");
		article.setSecret("hidden");
		article.password = "pass";
		article.apiKey = "key";
		return article;
	}

	@Getter
	@Setter
	public static class Article
	{
		@SecureWrite
		@Setter(lombok.AccessLevel.NONE)
		private Long id;

		private String title;

		@JsonProperty("body")
		private String content;

		@SecureWrite(rolesAllowed = "admin")
		private int views;

		@SecureIgnoreNull
		private String alwaysValid;

		@SecureWrite(rolesAllowed = "admin")
		private List<String> tags;

		@Getter(lombok.AccessLevel.NONE)
		private String owner;

		@Setter(lombok.AccessLevel.NONE)
		private String status = "draft";

		@SecureWrite(rolesAllowed = "admin")
		@Setter(lombok.AccessLevel.NONE)
		private String category;

		@JsonIgnore
		private String secret;

		@Getter(lombok.AccessLevel.NONE)
		@Setter(lombok.AccessLevel.NONE)
		String password;

		@Getter(lombok.AccessLevel.NONE)
		@Setter(lombok.AccessLevel.NONE)
		String apiKey;

		@SecureWrite(rolesAllowed = "admin")
		public String getOwner()
		{
			return owner;
		}

		@SecureWrite(rolesAllowed = { "editor", "admin" })
		public void setStatus(String status)
		{
			this.status = status;
		}

		@SecureWrite(rolesAllowed = "editor")
		public void setCategory(String category)
		{
			this.category = category;
		}

		public String getSummary()
		{
			return title+"/"+views;
		}

		public void setPassword(String password)
		{
			this.password = password;
		}

		@SecureWrite(rolesAllowed = "admin")
		public void setApiKey(String apiKey)
		{
			this.apiKey = apiKey;
		}
	}

	@Getter
	@Setter
	public static class Comment
	{
		private String text;
	}
}
