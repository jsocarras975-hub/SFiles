package com.superfiles.app.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_TEXT_BYTES = 1024 * 1024
private const val HIGHLIGHT_LIMIT = 100 * 1024

private data class CodeColors(val keyword: Color, val string: Color, val comment: Color, val number: Color)

/** One regex + the style to paint its matches. Earlier entries win on overlap. */
private fun rulesFor(ext: String, c: CodeColors): List<Pair<Regex, SpanStyle>> = when (ext) {
    "xml", "html" -> listOf(
        Regex("<!--[\\s\\S]*?-->") to SpanStyle(color = c.comment),
        Regex("\"[^\"]*\"") to SpanStyle(color = c.string),
        Regex("</?[A-Za-z][A-Za-z0-9_.:-]*|/?>") to SpanStyle(color = c.keyword),
        Regex("[A-Za-z0-9_:-]+(?==)") to SpanStyle(color = c.number),
    )
    "smali" -> listOf(
        Regex("#.*") to SpanStyle(color = c.comment),
        Regex("\"[^\"]*\"") to SpanStyle(color = c.string),
        Regex("^\\s*\\.[a-z-]+", RegexOption.MULTILINE) to SpanStyle(color = c.keyword),
        Regex("^\\s*:[A-Za-z0-9_\$-]+", RegexOption.MULTILINE) to SpanStyle(color = c.number),
    )
    "json" -> listOf(
        Regex("\"(?:[^\"\\\\]|\\\\.)*\"(?=\\s*:)") to SpanStyle(color = c.keyword),
        Regex("\"(?:[^\"\\\\]|\\\\.)*\"") to SpanStyle(color = c.string),
        Regex("-?\\b\\d+(\\.\\d+)?\\b") to SpanStyle(color = c.number),
        Regex("\\b(true|false|null)\\b") to SpanStyle(color = c.comment),
    )
    "sh", "py", "kt", "kts", "java", "js", "gradle" -> listOf(
        Regex("#.*|//.*") to SpanStyle(color = c.comment),
        Regex("\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'") to SpanStyle(color = c.string),
        Regex("\\b(fun|val|var|class|object|if|else|for|while|return|import|package|def|echo|fi|done|then|do|in|elif|case|esac|function|public|private|protected|static|void|int|new|try|catch|finally|throw|extends|implements)\\b") to SpanStyle(color = c.keyword),
        Regex("\\b\\d+(\\.\\d+)?\\b") to SpanStyle(color = c.number),
    )
    else -> emptyList()
}

/** Paints code with the rules for its extension. Plain text when the file is big or unknown. */
private fun highlightCode(text: String, ext: String, colors: CodeColors): AnnotatedString {
    val rules = rulesFor(ext, colors)
    if (rules.isEmpty() || text.length > HIGHLIGHT_LIMIT) return AnnotatedString(text)
    val builder = AnnotatedString.Builder(text)
    val covered = BooleanArray(text.length)
    for ((regex, style) in rules) {
        for (m in regex.findAll(text)) {
            val r = m.range
            if (r.isEmpty()) continue
            if (r.any { covered[it] }) continue
            for (i in r) covered[i] = true
            builder.addStyle(style, r.first, r.last + 1)
        }
    }
    return builder.toAnnotatedString()
}

/** A text editor (up to 1 MB) with syntax highlighting for XML, smali, JSON and code files. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEditorScreen(file: File, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var field by remember { mutableStateOf<TextFieldValue?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var dirty by remember { mutableStateOf(false) }
    val colors = CodeColors(
        keyword = MaterialTheme.colorScheme.primary,
        string = MaterialTheme.colorScheme.tertiary,
        comment = MaterialTheme.colorScheme.onSurfaceVariant,
        number = MaterialTheme.colorScheme.secondary,
    )
    val ext = file.extension.lowercase()

    LaunchedEffect(file) {
        withContext(Dispatchers.IO) {
            runCatching {
                if (file.length() > MAX_TEXT_BYTES) error("File is over 1 MB — too large to edit here")
                else {
                    val text = file.readText()
                    field = TextFieldValue(highlightCode(text, ext, colors))
                }
            }.onFailure { error = it.message ?: "Could not read file" }
        }
    }

    fun save() {
        val f = field ?: return
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { file.writeText(f.text) }.isSuccess
            }
            if (ok) {
                dirty = false
                Toast.makeText(ctx, "Saved", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(ctx, "Save failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(file.name + if (dirty) " *" else "") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = ::save, enabled = dirty) { Icon(Icons.Default.Save, "Save") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            when {
                error != null -> Text(error!!, modifier = Modifier.padding(16.dp))
                field == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> {
                    val f = field!!
                    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                        BasicTextField(
                            value = f,
                            onValueChange = {
                                // Re-highlight, keeping the cursor where the user left it.
                                val styled = highlightCode(it.text, ext, colors)
                                field = TextFieldValue(styled, it.selection, it.composition)
                                dirty = true
                            },
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp,
                            ),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}
