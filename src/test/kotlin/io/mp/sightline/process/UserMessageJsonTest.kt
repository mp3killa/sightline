package io.mp.sightline.process

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserMessageJsonTest {

    /**
     * The text-only line is a wire contract with the CLI: it must stay **byte-identical** to what
     * [ClaudeSession] always produced, or every existing conversation shape is silently at risk.
     */
    @Test fun textOnlyShapeIsUnchanged() {
        assertEquals(
            """{"type":"user","message":{"role":"user","content":"hello"}}""",
            UserMessageJson.userLine("hello"),
        )
    }

    @Test fun textIsEscaped() {
        assertEquals(
            """{"type":"user","message":{"role":"user","content":"a\"b\\c\nd\u0007"}}""",
            UserMessageJson.userLine("a\"b\\c\nd\u0007"),
        )
    }

    @Test fun imagesBecomeAContentArrayWithImagesBeforeText() {
        val line = UserMessageJson.userLine(
            "what is this?",
            listOf(UserMessageJson.ImageBlock("image/png", "QUJD")),
        )
        val content = JsonParser.parseString(line).asJsonObject
            .getAsJsonObject("message").getAsJsonArray("content")
        assertEquals(2, content.size())

        val image = content[0].asJsonObject
        assertEquals("image", image["type"].asString)
        val source = image.getAsJsonObject("source")
        assertEquals("base64", source["type"].asString)
        assertEquals("image/png", source["media_type"].asString)
        assertEquals("QUJD", source["data"].asString)

        // Image first, text last — the ordering the API documents as best for vision.
        val text = content[1].asJsonObject
        assertEquals("text", text["type"].asString)
        assertEquals("what is this?", text["text"].asString)
    }

    /** A user message need not contain text at all: "look at this" can be the image alone. */
    @Test fun blankTextWithImagesOmitsTheTextBlock() {
        val line = UserMessageJson.userLine("   ", listOf(UserMessageJson.ImageBlock("image/jpeg", "QQ==")))
        val content = JsonParser.parseString(line).asJsonObject
            .getAsJsonObject("message").getAsJsonArray("content")
        assertEquals(1, content.size())
        assertEquals("image", content[0].asJsonObject["type"].asString)
        assertFalse(line.contains("\"text\""))
    }

    @Test fun multipleImagesKeepPasteOrder() {
        val line = UserMessageJson.userLine(
            "both screens",
            listOf(
                UserMessageJson.ImageBlock("image/png", "Zmlyc3Q="),
                UserMessageJson.ImageBlock("image/jpeg", "c2Vjb25k"),
            ),
        )
        val content = JsonParser.parseString(line).asJsonObject
            .getAsJsonObject("message").getAsJsonArray("content")
        assertEquals(3, content.size())
        assertEquals("Zmlyc3Q=", content[0].asJsonObject.getAsJsonObject("source")["data"].asString)
        assertEquals("c2Vjb25k", content[1].asJsonObject.getAsJsonObject("source")["data"].asString)
        assertEquals("text", content[2].asJsonObject["type"].asString)
    }

    /** Every produced line must be a single JSON document with no raw newlines — it travels on one stdin line. */
    @Test fun linesNeverContainRawNewlines() {
        val withImages = UserMessageJson.userLine("a\nb", listOf(UserMessageJson.ImageBlock("image/png", "QQ==")))
        assertFalse(withImages.contains('\n'))
        assertFalse(UserMessageJson.userLine("a\nb").contains('\n'))
        assertTrue(JsonParser.parseString(withImages).isJsonObject)
    }
}
