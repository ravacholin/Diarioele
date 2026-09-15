package com.capo.diarioclase.ui.archive

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.launch
import com.capo.diarioclase.processing.semantic.ConnectionResult
import com.capo.diarioclase.processing.semantic.InferenceProvider
import com.capo.diarioclase.processing.semantic.ProviderSettingsController
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.capo.diarioclase.data.db.CerLevel
import com.capo.diarioclase.processing.evidence.InterpretationMode

@Composable
fun ArchiveScreen(state: ArchiveUiState, viewModel: ArchiveViewModel, onCapture: () -> Unit, onSettings: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    BackHandler(enabled = state.selectedDiary != null) {
        when {
            state.deleteConfirmation != null -> viewModel.cancelDelete()
            state.editableFields != null -> viewModel.cancelEdit()
            else -> viewModel.closeDiary()
        }
    }
    LazyColumn(
        Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Spacer(Modifier.height(24.dp))
            Text("DIARIO DE CLASE", fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Spacer(Modifier.height(24.dp))
            Text("DIARIOS GUARDADOS", fontSize = 32.sp, fontWeight = FontWeight.Black)
        }
        if (state.selectedDiary == null) {
            item {
                ArchiveButton("NUEVO DÍA", !state.busy, onCapture)
                Spacer(Modifier.height(8.dp))
                ArchiveButton("CONFIGURACIÓN", !state.busy, onSettings, filled = false)
            }
            item {
                ArchiveTextField("BUSCAR POR FECHA, NIVEL O CONTENIDO", state.query, viewModel::onQuery, singleLine = true)
                if (state.searching) Text("Buscando…", color = Color.LightGray)
                else if (state.entries.isEmpty()) Text(
                    if (state.query.isBlank()) "Todavía no hay diarios guardados." else "No hay diarios que coincidan con la búsqueda.",
                    color = Color.LightGray,
                )
            }
            items(state.entries, key = { it.id }) { entry ->
                Column(
                    Modifier.fillMaxWidth().border(1.dp, Color.DarkGray)
                        .clickable(enabled = !state.busy, role = Role.Button) { viewModel.selectDiary(entry.id) }.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(entry.pedagogicalDate, fontSize = 22.sp, fontWeight = FontWeight.Black)
                    entry.level?.let { Text(it.name, color = Color.LightGray) }
                    Text(entry.topics.ifBlank { "Sin temas registrados" }, color = Color.LightGray)
                    Text("ABRIR", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        } else {
            val entry = state.selectedDiary
            item {
                ArchiveButton("VOLVER AL ARCHIVO", !state.busy, viewModel::closeDiary, filled = false)
                Spacer(Modifier.height(18.dp))
                Text(entry.pedagogicalDate, fontSize = 26.sp, fontWeight = FontWeight.Black)
                entry.level?.let { Text("NIVEL ${it.name}", color = Color.LightGray) }
            }
            val fields = state.editableFields
            if (fields == null) {
                item {
                    PermanentField("TEMAS", entry.topics)
                    PermanentField("ACTIVIDADES REALIZADAS", entry.activities)
                    PermanentField("PÁGINAS Y EJERCICIOS", entry.pages)
                    if (entry.completedExercises.isNotBlank()) {
                        PermanentField("EJERCICIOS HECHOS", entry.completedExercises)
                    }
                    PermanentField("TAREA", entry.homework)
                }
                item {
                    ArchiveButton("EDITAR", !state.busy, viewModel::startEditing)
                    Spacer(Modifier.height(8.dp))
                    ArchiveButton("COPIAR", !state.busy, {
                        viewModel.copySelected()?.let { text ->
                            clipboard.setText(AnnotatedString(text))
                            viewModel.onCopied()
                        }
                    }, filled = false)
                    Spacer(Modifier.height(8.dp))
                    ArchiveButton("ELIMINAR DIARIO", !state.busy, viewModel::requestDelete, filled = false)
                }
            } else {
                item {
                    ArchiveTextField("FECHA (AAAA-MM-DD)", fields.pedagogicalDate, { viewModel.onEdit(fields.copy(pedagogicalDate = it)) }, !state.busy, singleLine = true)
                    Text("NIVEL", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        CerLevel.entries.forEach { level ->
                            OutlinedButton(
                                onClick = { viewModel.onEdit(fields.copy(level = level)) },
                                enabled = !state.busy, shape = RectangleShape, modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(0.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (fields.level == level) Color.White else Color.Black,
                                    contentColor = if (fields.level == level) Color.Black else Color.White,
                                ),
                            ) { Text(level.name, fontSize = 11.sp) }
                        }
                    }
                    TextButton(onClick = { viewModel.onEdit(fields.copy(level = null)) }, enabled = !state.busy, shape = RectangleShape) { Text("SIN NIVEL") }
                    ArchiveTextField("TEMAS", fields.topics, { viewModel.onEdit(fields.copy(topics = it)) }, !state.busy)
                    ArchiveTextField("ACTIVIDADES REALIZADAS", fields.activities, { viewModel.onEdit(fields.copy(activities = it)) }, !state.busy)
                    ArchiveTextField("PÁGINAS Y EJERCICIOS", fields.pages, { viewModel.onEdit(fields.copy(pages = it)) }, !state.busy)
                    if (fields.completedExercises.isNotBlank()) {
                        ArchiveTextField("EJERCICIOS HECHOS", fields.completedExercises, { viewModel.onEdit(fields.copy(completedExercises = it)) }, !state.busy)
                    }
                    ArchiveTextField("TAREA", fields.homework, { viewModel.onEdit(fields.copy(homework = it)) }, !state.busy)
                }
                item {
                    ArchiveButton("GUARDAR", !state.busy, viewModel::saveEdit)
                    Spacer(Modifier.height(8.dp))
                    ArchiveButton("CANCELAR EDICIÓN", !state.busy, viewModel::cancelEdit, filled = false)
                }
            }
        }
        item { state.message?.let { Text(it, color = Color.LightGray) }; Spacer(Modifier.height(24.dp)) }
    }
    state.deleteConfirmation?.let { target ->
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete, shape = RectangleShape, containerColor = Color.Black,
            title = { Text("ELIMINAR DIARIO") },
            text = { Text("¿Eliminar definitivamente el diario del ${target.pedagogicalDate}? Esta acción no se puede deshacer.") },
            confirmButton = { TextButton(onClick = viewModel::confirmDelete, enabled = !state.busy, shape = RectangleShape) { Text("CONFIRMAR ELIMINACIÓN") } },
            dismissButton = { TextButton(onClick = viewModel::cancelDelete, enabled = !state.busy, shape = RectangleShape) { Text("CANCELAR") } },
        )
    }
}

@Composable
fun SettingsScreen(
    state: ArchiveUiState,
    onMode: (InterpretationMode) -> Unit,
    onArchive: () -> Unit,
    providerController: ProviderSettingsController? = null,
) {
    BackHandler(onBack = onArchive)
    LazyColumn(
        Modifier.fillMaxSize().background(Color.Black).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Text("CONFIGURACIÓN", fontSize = 32.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(12.dp))
            Text("MODO DE INTERPRETACIÓN", fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.height(8.dp))
            Text("La selección se guarda al instante y se usa al procesar audio. No modifica los diarios archivados ni reemplaza tus ediciones manuales.", color = Color.LightGray)
        }
        item { ModeChoice("Conservador", "Incluye solo datos explícitos. Deja las dudas por confirmar.", InterpretationMode.CONSERVATIVE, state, onMode) }
        item { ModeChoice("Equilibrado", "Incluye datos explícitos y relaciones claras dentro de lo dicho. Mantiene las dudas por confirmar.", InterpretationMode.BALANCED, state, onMode) }
        item { ModeChoice("Exhaustivo", "Recoge más detalles respaldados por la transcripción. Puede requerir una revisión más cuidadosa; no inventa información.", InterpretationMode.EXHAUSTIVE, state, onMode) }
        if (providerController != null) {
            item {
                Spacer(Modifier.height(8.dp))
                Text("INTERPRETACIÓN CON IA (OPCIONAL)", fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(8.dp))
                Text("El audio nunca se envía. Con tu consentimiento, se envía solo texto transcripto (con ids artificiales) a un proveedor gratuito para armar la ficha. Sin proveedores, la interpretación es 100% local.", color = Color.LightGray)
            }
            item { ProviderConfigSection(providerController) }
        }
        item { state.message?.let { Text(it, color = Color.LightGray) } }
        item { ArchiveButton("DIARIOS GUARDADOS", !state.busy, onArchive, filled = false) }
    }
}

@Composable
private fun ProviderConfigSection(controller: ProviderSettingsController) {
    val scope = rememberCoroutineScope()
    var views by remember { mutableStateOf(controller.providers()) }
    fun refresh() { views = controller.providers() }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        views.forEach { view ->
            var key by remember(view.provider) { mutableStateOf("") }
            var testResult by remember(view.provider) { mutableStateOf<ConnectionResult?>(null) }
            var testing by remember(view.provider) { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().border(1.dp, Color.DarkGray).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(providerLabel(view.provider), fontSize = 18.sp, fontWeight = FontWeight.Black)
                Text("Modelo: ${view.modelId}", color = Color.LightGray, fontSize = 12.sp)
                view.keyLast4?.let { Text("Clave guardada ····$it", color = Color.LightGray, fontSize = 12.sp) }

                ArchiveButton(if (view.enabled) "ACTIVADO" else "ACTIVAR", true, { controller.setEnabled(view.provider, !view.enabled); refresh() }, filled = view.enabled)
                ArchiveButton(if (view.consented) "CONSENTIMIENTO DADO" else "DAR CONSENTIMIENTO", true, { controller.setConsent(view.provider, !view.consented); refresh() }, filled = view.consented)

                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("CLAVE") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    shape = RectangleShape,
                    modifier = Modifier.fillMaxWidth(),
                )
                ArchiveButton("GUARDAR CLAVE", key.isNotBlank(), {
                    controller.saveKey(view.provider, key.toCharArray())
                    key = ""
                    testResult = null
                    refresh()
                })
                if (view.hasKey) {
                    ArchiveButton(if (testing) "PROBANDO…" else "PROBAR CONEXIÓN", !testing, {
                        testing = true
                        testResult = null
                        scope.launch {
                            testResult = controller.testConnection(view.provider)
                            testing = false
                        }
                    }, filled = false)
                    ArchiveButton("BORRAR CLAVE", !testing, { controller.clearKey(view.provider); testResult = null; refresh() }, filled = false)
                }
                if (testing) Text("Probando conexión…", color = Color.LightGray, fontSize = 12.sp)
                testResult?.let { Text(connectionLabel(it), color = Color.LightGray, fontSize = 12.sp) }
                if (view.hasKey && (!view.enabled || !view.consented)) {
                    Text("Para usarlo al procesar el audio: activá y dá consentimiento arriba.", color = Color.LightGray, fontSize = 12.sp)
                }
            }
        }
    }
}

private fun providerLabel(provider: InferenceProvider) = when (provider) {
    InferenceProvider.GEMINI -> "GEMINI"
    InferenceProvider.GROQ -> "GROQ"
    InferenceProvider.OPENROUTER -> "OPENROUTER (FREE)"
}

private fun connectionLabel(result: ConnectionResult) = when (result) {
    ConnectionResult.OK -> "Conexión correcta."
    ConnectionResult.INVALID_KEY -> "La clave no es válida."
    ConnectionResult.BILLING_WARNING -> "Advertencia: la cuenta podría tener facturación. Usá un proyecto sin facturación."
    ConnectionResult.QUOTA -> "Límite gratuito agotado por ahora. Probá más tarde."
    ConnectionResult.NO_NETWORK -> "Sin conexión a internet."
    ConnectionResult.MODEL_OR_REQUEST -> "La clave parece válida, pero el proveedor rechazó el pedido (modelo o formato). Revisá el modelo configurado."
    ConnectionResult.UNAVAILABLE -> "No se pudo conectar en este momento."
    ConnectionResult.NOT_CONFIGURED -> "Falta guardar la clave."
}

@Composable
private fun ModeChoice(label: String, consequence: String, mode: InterpretationMode, state: ArchiveUiState, onMode: (InterpretationMode) -> Unit) {
    Column(Modifier.fillMaxWidth().border(1.dp, if (state.mode == mode) Color.White else Color.DarkGray).padding(16.dp)) {
        ArchiveButton(label, !state.busy, { onMode(mode) }, filled = state.mode == mode)
        Spacer(Modifier.height(10.dp))
        if (state.mode == mode) Text("SELECCIONADO", fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(consequence, color = Color.LightGray)
    }
}

@Composable
private fun PermanentField(label: String, value: String) {
    Column(Modifier.fillMaxWidth().border(1.dp, Color.DarkGray).padding(12.dp)) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(6.dp))
        Text(value.ifBlank { "—" }, color = Color.LightGray)
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun ArchiveTextField(label: String, value: String, onValue: (String) -> Unit, enabled: Boolean = true, singleLine: Boolean = false) {
    Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    OutlinedTextField(
        value = value, onValueChange = onValue, enabled = enabled, shape = RectangleShape,
        modifier = Modifier.fillMaxWidth(), singleLine = singleLine, minLines = if (singleLine) 1 else 2,
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White, unfocusedBorderColor = Color.DarkGray),
    )
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun ArchiveButton(text: String, enabled: Boolean, onClick: () -> Unit, filled: Boolean = true) {
    Button(
        onClick = onClick, enabled = enabled, shape = RectangleShape,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).border(1.dp, Color.White),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (filled) Color.White else Color.Black,
            contentColor = if (filled) Color.Black else Color.White,
            disabledContainerColor = Color.DarkGray, disabledContentColor = Color.LightGray,
        ),
    ) { Text(text, fontWeight = FontWeight.Bold) }
}
