package net.dryuf.jackson.serialize;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.WebApplicationException;
import lombok.Getter;
import lombok.Setter;
import net.dryuf.base.security.identity.FixedIdentityContext;
import net.dryuf.base.security.identity.IdentityContext;
import net.dryuf.jackson.annotation.SecureWriteField;
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


public class SecureFieldWriterTest
{
	private static final IdentityContext ANONYMOUS = identity();
	private static final IdentityContext EDITOR = identity("editor");
	private static final IdentityContext ADMIN = identity("admin");

	private final ObjectMapper objectMapper = JsonMapper.builder().build();

	private final SecureFieldWriter writer = new SecureFieldWriter(objectMapper);

	@BeforeClass
	public void setUp()
	{
		StubRuntimeDelegate.install();
	}

	@Test
	public void apply_unrestricted_written()
	{
		Article article = article();

		Article result = writer.apply(article, json("{'title':'New'}"), SecureFieldWriter.Mode.MERGE, ANONYMOUS);

		assertSame(result, article);
		assertEquals(article.getTitle(), "New");
	}

	@Test
	public void apply_jsonPropertyName_written()
	{
		Article article = article();

		writer.apply(article, json("{'body':'Text'}"), SecureFieldWriter.Mode.MERGE, ANONYMOUS);

		assertEquals(article.getContent(), "Text");
	}

	@Test
	public void apply_javaNameOfRenamed_badRequest()
	{
		WebApplicationException ex = expectThrows(BadRequestException.class, () ->
			writer.apply(article(), json("{'content':'Text'}"), SecureFieldWriter.Mode.MERGE, ANONYMOUS));

		assertError(ex, 400, "Unknown field: content");
	}

	@Test
	public void apply_unknownField_badRequest()
	{
		WebApplicationException ex = expectThrows(BadRequestException.class, () ->
			writer.apply(article(), json("{'unknown':1}"), SecureFieldWriter.Mode.MERGE, ADMIN));

		assertError(ex, 400, "Unknown field: unknown");
	}

	@Test
	public void apply_jsonIgnored_badRequest()
	{
		Article article = article();

		expectThrows(BadRequestException.class, () ->
			writer.apply(article, json("{'secret':'x'}"), SecureFieldWriter.Mode.MERGE, ADMIN));
		assertEquals(article.getSecret(), "hidden");
	}

	@Test
	public void apply_restrictedWithRole_written()
	{
		Article article = article();

		writer.apply(article, json("{'views':99,'tags':['x']}"), SecureFieldWriter.Mode.MERGE, ADMIN);

		assertEquals(article.getViews(), 99);
		assertEquals(article.getTags(), List.of("x"));
	}

	@Test
	public void apply_restrictedWithoutRole_forbidden()
	{
		Article article = article();

		WebApplicationException ex = expectThrows(ForbiddenException.class, () ->
			writer.apply(article, json("{'title':'New','views':99,'tags':['x']}"), SecureFieldWriter.Mode.MERGE, EDITOR));

		assertError(ex, 403, "Not allowed to modify fields [views, tags] with roles [editor]");
		assertEquals(article.getTitle(), "Old");
		assertEquals(article.getViews(), 10);
	}

	@Test
	public void apply_restrictedSameValue_accepted()
	{
		Article article = article();

		writer.apply(article, json("{'title':'New','views':10,'tags':['a','b']}"), SecureFieldWriter.Mode.MERGE, EDITOR);

		assertEquals(article.getTitle(), "New");
		assertEquals(article.getViews(), 10);
		assertEquals(article.getTags(), List.of("a", "b"));
	}

	@Test
	public void apply_restrictedSameNumberDifferentScale_accepted()
	{
		Article article = article();

		writer.apply(article, json("{'views':10.0}"), SecureFieldWriter.Mode.MERGE, EDITOR);

		assertEquals(article.getViews(), 10);
	}

	@Test
	public void apply_restrictedSameNull_accepted()
	{
		Article article = article();
		article.setTags(null);

		writer.apply(article, json("{'tags':null}"), SecureFieldWriter.Mode.MERGE, EDITOR);

		assertNull(article.getTags());
	}

	@Test
	public void apply_restrictedDifferentOrder_forbidden()
	{
		expectThrows(ForbiddenException.class, () ->
			writer.apply(article(), json("{'tags':['b','a']}"), SecureFieldWriter.Mode.MERGE, EDITOR));
	}

	@Test
	public void apply_emptyRolesAllowed_readOnlyForAll()
	{
		Article article = article();

		WebApplicationException ex = expectThrows(ForbiddenException.class, () ->
			writer.apply(article, json("{'id':2}"), SecureFieldWriter.Mode.MERGE, ADMIN));

		assertError(ex, 403, "Not allowed to modify fields [id] with roles [admin]");
		assertEquals(article.getId(), Long.valueOf(1));
	}

	@Test
	public void apply_emptyRolesAllowedSameValue_accepted()
	{
		Article article = article();

		writer.apply(article, json("{'id':1}"), SecureFieldWriter.Mode.MERGE, ADMIN);

		assertEquals(article.getId(), Long.valueOf(1));
	}

	@Test
	public void apply_annotatedSetter_anyListedRole()
	{
		Article byEditor = article();
		Article byAdmin = article();

		writer.apply(byEditor, json("{'status':'published'}"), SecureFieldWriter.Mode.MERGE, EDITOR);
		writer.apply(byAdmin, json("{'status':'archived'}"), SecureFieldWriter.Mode.MERGE, ADMIN);

		assertEquals(byEditor.getStatus(), "published");
		assertEquals(byAdmin.getStatus(), "archived");
		expectThrows(ForbiddenException.class, () ->
			writer.apply(article(), json("{'status':'published'}"), SecureFieldWriter.Mode.MERGE, ANONYMOUS));
	}

	@Test
	public void apply_annotatedGetter_restricted()
	{
		Article article = article();

		writer.apply(article, json("{'owner':'bob'}"), SecureFieldWriter.Mode.MERGE, ADMIN);

		assertEquals(article.getOwner(), "bob");
		expectThrows(ForbiddenException.class, () ->
			writer.apply(article(), json("{'owner':'bob'}"), SecureFieldWriter.Mode.MERGE, EDITOR));
	}

	@Test
	public void apply_setterAndFieldAnnotated_setterWins()
	{
		Article article = article();

		writer.apply(article, json("{'category':'news'}"), SecureFieldWriter.Mode.MERGE, EDITOR);

		assertEquals(article.getCategory(), "news");
		expectThrows(ForbiddenException.class, () ->
			writer.apply(article(), json("{'category':'news'}"), SecureFieldWriter.Mode.MERGE, ADMIN));
	}

	@Test
	public void apply_readOnlyComputedDifferent_forbidden()
	{
		expectThrows(ForbiddenException.class, () ->
			writer.apply(article(), json("{'summary':'other'}"), SecureFieldWriter.Mode.MERGE, ADMIN));
	}

	@Test
	public void apply_readOnlyComputedSame_ignored()
	{
		Article article = article();

		writer.apply(article, json("{'summary':'Old/10'}"), SecureFieldWriter.Mode.MERGE, ADMIN);

		assertEquals(article.getSummary(), "Old/10");
	}

	@Test
	public void apply_writeOnlyUnrestricted_written()
	{
		Article article = article();

		writer.apply(article, json("{'password':'pwd'}"), SecureFieldWriter.Mode.MERGE, ANONYMOUS);

		assertEquals(article.password, "pwd");
	}

	@Test
	public void apply_writeOnlyRestrictedWithoutRole_forbidden()
	{
		expectThrows(ForbiddenException.class, () ->
			writer.apply(article(), json("{'apiKey':'key'}"), SecureFieldWriter.Mode.MERGE, EDITOR));
	}

	@Test
	public void apply_invalidValue_badRequest()
	{
		WebApplicationException ex = expectThrows(BadRequestException.class, () ->
			writer.apply(article(), json("{'views':'many'}"), SecureFieldWriter.Mode.MERGE, ADMIN));

		assertEquals(ex.getResponse().getStatus(), 400);
		assertTrue(ex.getMessage().startsWith("Invalid input: "), ex.getMessage());
	}

	@Test
	public void apply_merge_absentPreserved()
	{
		Article article = article();

		writer.apply(article, json("{'title':'New'}"), SecureFieldWriter.Mode.MERGE, ADMIN);

		assertEquals(article.getContent(), "Body");
		assertEquals(article.getStatus(), "draft");
		assertEquals(article.getViews(), 10);
	}

	@Test
	public void apply_create_absentPreserved()
	{
		Article article = new Article();

		writer.apply(article, json("{'title':'New'}"), SecureFieldWriter.Mode.CREATE, ADMIN);

		assertEquals(article.getTitle(), "New");
		assertEquals(article.getStatus(), "draft");
	}

	@Test
	public void apply_mergeExplicitNull_cleared()
	{
		Article article = article();

		writer.apply(article, json("{'body':null}"), SecureFieldWriter.Mode.MERGE, ANONYMOUS);

		assertNull(article.getContent());
	}

	@Test
	public void apply_replace_absentWritableKeep()
	{
		Article article = article();

		writer.apply(article, json("{'title':'New'}"), SecureFieldWriter.Mode.REPLACE, EDITOR);

		assertEquals(article.getTitle(), "New");
		assertNotNull(article.getContent());
		assertNotNull(article.getStatus());
		assertNotNull(article.getCategory());
		assertNotNull(article.password);
	}

	@Test
	public void apply_replace_absentRestrictedPreserved()
	{
		Article article = article();

		writer.apply(article, json("{'title':'New'}"), SecureFieldWriter.Mode.REPLACE, EDITOR);

		assertEquals(article.getId(), Long.valueOf(1));
		assertEquals(article.getViews(), 10);
		assertEquals(article.getTags(), List.of("a", "b"));
		assertEquals(article.getOwner(), "alice");
		assertEquals(article.apiKey, "key");
		assertEquals(article.getSecret(), "hidden");
	}

	@Test
	public void apply_replaceAbsentPrimitive_keep()
	{
		Article article = article();

		writer.apply(article, json("{}"), SecureFieldWriter.Mode.REPLACE, ADMIN);

		assertEquals(article.getViews(), 10);
		assertNotNull(article.getTags());
	}

	@Test
	public void apply_repeated_sameResult()
	{
		Article first = article();
		Article second = article();

		writer.apply(first, json("{'title':'One'}"), SecureFieldWriter.Mode.MERGE, ANONYMOUS);
		writer.apply(second, json("{'title':'Two'}"), SecureFieldWriter.Mode.MERGE, ANONYMOUS);

		assertEquals(first.getTitle(), "One");
		assertEquals(second.getTitle(), "Two");
	}

	@Test
	public void apply_differentClasses_introspectedSeparately()
	{
		Comment comment = new Comment();

		writer.apply(comment, json("{'text':'Hi'}"), SecureFieldWriter.Mode.MERGE, ANONYMOUS);

		assertEquals(comment.getText(), "Hi");
		expectThrows(BadRequestException.class, () ->
			writer.apply(comment, json("{'title':'x'}"), SecureFieldWriter.Mode.MERGE, ANONYMOUS));
	}

	@Test
	public void error_built_statusAndEntity()
	{
		WebApplicationException ex = SecureFieldWriter.error(ForbiddenException::new,
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
		@SecureWriteField
		@Setter(lombok.AccessLevel.NONE)
		private Long id;

		private String title;

		@JsonProperty("body")
		private String content;

		@SecureWriteField(rolesAllowed = "admin")
		private int views;

		@SecureWriteField(rolesAllowed = "admin")
		private List<String> tags;

		@Getter(lombok.AccessLevel.NONE)
		private String owner;

		@Setter(lombok.AccessLevel.NONE)
		private String status = "draft";

		@SecureWriteField(rolesAllowed = "admin")
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

		@SecureWriteField(rolesAllowed = "admin")
		public String getOwner()
		{
			return owner;
		}

		@SecureWriteField(rolesAllowed = { "editor", "admin" })
		public void setStatus(String status)
		{
			this.status = status;
		}

		@SecureWriteField(rolesAllowed = "editor")
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

		@SecureWriteField(rolesAllowed = "admin")
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
