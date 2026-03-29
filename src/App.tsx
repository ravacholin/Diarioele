import { useState, useRef, useEffect } from 'react';
import { Mic, Square, Loader2, Trash2, ChevronDown, ChevronUp, Copy, Check, Search, Settings, X, AlertCircle } from 'lucide-react';
import { GoogleGenAI } from '@google/genai';
import ReactMarkdown from 'react-markdown';
import { format } from 'date-fns';
import { es } from 'date-fns/locale';
import { ClassSummary } from './types';

// IndexedDB utility for storing large Blobs (audio)
const DB_NAME = 'DiarioAI_DB';
const STORE_NAME = 'pending_audio';

const initDB = (): Promise<IDBDatabase> => {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, 1);
    request.onerror = () => reject(request.error);
    request.onsuccess = () => resolve(request.result);
    request.onupgradeneeded = (e) => {
      const db = (e.target as IDBOpenDBRequest).result;
      if (!db.objectStoreNames.contains(STORE_NAME)) {
        db.createObjectStore(STORE_NAME);
      }
    };
  });
};

const saveAudioToDB = async (blob: Blob, duration: number) => {
  try {
    const db = await initDB();
    const tx = db.transaction(STORE_NAME, 'readwrite');
    const store = tx.objectStore(STORE_NAME);
    store.put({ blob, duration }, 'current_audio');
    return new Promise((resolve, reject) => {
      tx.oncomplete = resolve;
      tx.onerror = () => reject(tx.error);
    });
  } catch (e) {
    console.error('Failed to save audio to DB', e);
  }
};

const getAudioFromDB = async (): Promise<{ blob: Blob, duration: number } | null> => {
  try {
    const db = await initDB();
    const tx = db.transaction(STORE_NAME, 'readonly');
    const store = tx.objectStore(STORE_NAME);
    const request = store.get('current_audio');
    return new Promise((resolve, reject) => {
      request.onsuccess = () => resolve(request.result || null);
      request.onerror = () => reject(request.error);
    });
  } catch (e) {
    console.error('Failed to get audio from DB', e);
    return null;
  }
};

const clearAudioFromDB = async () => {
  try {
    const db = await initDB();
    const tx = db.transaction(STORE_NAME, 'readwrite');
    const store = tx.objectStore(STORE_NAME);
    store.delete('current_audio');
  } catch (e) {
    console.error('Failed to clear audio from DB', e);
  }
};

// We will initialize the AI client dynamically to allow local storage overrides
const getAIClient = () => {
  const storedKey = localStorage.getItem('GEMINI_API_KEY');
  const envKey = process.env.GEMINI_API_KEY;
  const key = storedKey || envKey;
  return key ? new GoogleGenAI({ apiKey: key }) : null;
};

export default function App() {
  const [isRecording, setIsRecording] = useState(false);
  const [isProcessing, setIsProcessing] = useState(false);
  const [recordingTime, setRecordingTime] = useState(0);
  const [summaries, setSummaries] = useState<ClassSummary[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<string | 'ALL' | null>(null);
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [copiedId, setCopiedId] = useState<string | null>(null);
  const [copiedExId, setCopiedExId] = useState<string | null>(null);
  const [isAwaitingPrompt, setIsAwaitingPrompt] = useState(false);
  const [customPrompt, setCustomPrompt] = useState('');
  const [searchQuery, setSearchQuery] = useState('');
  const [showSettings, setShowSettings] = useState(false);
  const [localApiKey, setLocalApiKey] = useState(localStorage.getItem('GEMINI_API_KEY') || '');
  const [pendingAudioBlob, setPendingAudioBlob] = useState<Blob | null>(null);
  const [pendingDuration, setPendingDuration] = useState(0);
  const [recoveredState, setRecoveredState] = useState(false);
  const [processingMessage, setProcessingMessage] = useState<string | null>(null);

  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const audioChunksRef = useRef<Blob[]>([]);
  const timerRef = useRef<number | null>(null);
  const startTimeRef = useRef<number | null>(null);
  const wakeLockRef = useRef<any>(null);

  const requestWakeLock = async () => {
    try {
      if ('wakeLock' in navigator) {
        wakeLockRef.current = await (navigator as any).wakeLock.request('screen');
      }
    } catch (err) {
      console.log('Wake Lock error:', err);
    }
  };

  const releaseWakeLock = () => {
    if (wakeLockRef.current) {
      wakeLockRef.current.release().catch(console.error);
      wakeLockRef.current = null;
    }
  };

  useEffect(() => {
    const saved = localStorage.getItem('class_summaries');
    if (saved) {
      try {
        setSummaries(JSON.parse(saved));
      } catch (e) {
        console.error('Failed to parse summaries', e);
      }
    }

    // Check for recovered state on mount
    const checkRecoveredState = async () => {
      const savedState = localStorage.getItem('pending_processing_state');
      if (savedState) {
        try {
          const state = JSON.parse(savedState);
          const audioData = await getAudioFromDB();
          
          if (audioData && audioData.blob) {
            setPendingAudioBlob(audioData.blob);
            setPendingDuration(audioData.duration);
            setCustomPrompt(state.customPrompt || '');
            setIsAwaitingPrompt(true);
            setRecoveredState(true);
            // We don't automatically start processing, we let the user review and click process
          } else {
            // Clean up if DB is empty but localStorage has state
            localStorage.removeItem('pending_processing_state');
          }
        } catch (e) {
          console.error('Failed to recover state', e);
          localStorage.removeItem('pending_processing_state');
        }
      }
    };
    
    checkRecoveredState();
  }, []);

  useEffect(() => {
    localStorage.setItem('class_summaries', JSON.stringify(summaries));
  }, [summaries]);

  // Save prompt state whenever it changes if we have pending audio
  useEffect(() => {
    if (pendingAudioBlob && isAwaitingPrompt) {
      localStorage.setItem('pending_processing_state', JSON.stringify({
        customPrompt,
        timestamp: Date.now()
      }));
    }
  }, [customPrompt, pendingAudioBlob, isAwaitingPrompt]);

  // Sync timer when app comes back to foreground
  useEffect(() => {
    const handleVisibilityChange = () => {
      if (document.visibilityState === 'visible' && isRecording && startTimeRef.current) {
        setRecordingTime(Math.floor((Date.now() - startTimeRef.current) / 1000));
      }
    };
    
    document.addEventListener('visibilitychange', handleVisibilityChange);
    return () => document.removeEventListener('visibilitychange', handleVisibilityChange);
  }, [isRecording]);

  const formatTime = (seconds: number) => {
    const h = Math.floor(seconds / 3600);
    const m = Math.floor((seconds % 3600) / 60);
    const s = seconds % 60;
    if (h > 0) {
      return `${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
    }
    return `${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
  };

  const startRecording = async () => {
    try {
      setError(null);
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      
      const options = {
        mimeType: 'audio/webm;codecs=opus',
        audioBitsPerSecond: 16000 
      };
      
      const mediaRecorder = new MediaRecorder(stream, MediaRecorder.isTypeSupported(options.mimeType) ? options : undefined);
      mediaRecorderRef.current = mediaRecorder;
      audioChunksRef.current = [];

      mediaRecorder.ondataavailable = (event) => {
        if (event.data.size > 0) {
          audioChunksRef.current.push(event.data);
        }
      };

      mediaRecorder.onstop = handleStopRecording;

      mediaRecorder.start(5000); // Increased chunk size to reduce overhead
      setIsRecording(true);
      setRecordingTime(0);
      startTimeRef.current = Date.now();
      await requestWakeLock();
      
      // Use absolute time difference to survive background throttling
      timerRef.current = window.setInterval(() => {
        if (startTimeRef.current) {
          setRecordingTime(Math.floor((Date.now() - startTimeRef.current) / 1000));
        }
      }, 1000);

    } catch (err) {
      console.error('Error accessing microphone:', err);
      setError('ACCESO DENEGADO AL MICRÓFONO. VERIFIQUE LOS PERMISOS.');
    }
  };

  const stopRecording = () => {
    if (mediaRecorderRef.current && isRecording) {
      mediaRecorderRef.current.stop();
      mediaRecorderRef.current.stream.getTracks().forEach(track => track.stop());
      setIsRecording(false);
      if (timerRef.current) {
        clearInterval(timerRef.current);
      }
      startTimeRef.current = null;
      releaseWakeLock();
    }
  };

  const blobToBase64 = (blob: Blob): Promise<string> => {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onloadend = () => {
        const base64String = (reader.result as string).split(',')[1];
        resolve(base64String);
      };
      reader.onerror = reject;
      reader.readAsDataURL(blob);
    });
  };

  const handleStopRecording = async () => {
    const audioBlob = new Blob(audioChunksRef.current, { type: 'audio/webm' });
    const duration = recordingTime;
    
    setPendingAudioBlob(audioBlob);
    setPendingDuration(duration);
    setIsAwaitingPrompt(true);
    
    // Save to persistent storage immediately
    await saveAudioToDB(audioBlob, duration);
    localStorage.setItem('pending_processing_state', JSON.stringify({
      customPrompt: '',
      timestamp: Date.now()
    }));
  };

  const cancelProcessing = async () => {
    setIsAwaitingPrompt(false);
    setPendingAudioBlob(null);
    setCustomPrompt('');
    setRecordingTime(0);
    setRecoveredState(false);
    
    // Clear persistent storage
    await clearAudioFromDB();
    localStorage.removeItem('pending_processing_state');
  };

  const processAudio = async (mode: 'full' | 'exercises_only' = 'full') => {
    if (!pendingAudioBlob) return;
    
    setIsProcessing(true);
    setIsAwaitingPrompt(false);
    setError(null);
    setProcessingMessage('Preparando audio...');

    const ai = getAIClient();
    if (!ai) {
      setError('FALTA LA CLAVE DE API DE GEMINI. Por favor, configúrala en el menú de Ajustes (icono de engranaje).');
      setIsProcessing(false);
      setIsAwaitingPrompt(true); // Return to prompt screen so audio is not lost
      return;
    }

    await requestWakeLock();
    let success = false;

    try {
      const CHUNK_SIZE = 10 * 1024 * 1024; // 10MB chunks
      const totalChunks = Math.ceil(pendingAudioBlob.size / CHUNK_SIZE);
      const partialResults = [];

      for (let i = 0; i < totalChunks; i++) {
        setProcessingMessage(totalChunks > 1 ? `Procesando parte ${i + 1} de ${totalChunks}...` : 'Procesando audio...');
        
        const start = i * CHUNK_SIZE;
        const end = Math.min(start + CHUNK_SIZE, pendingAudioBlob.size);
        const chunkBlob = pendingAudioBlob.slice(start, end, pendingAudioBlob.type || 'audio/webm');
        const base64Audio = await blobToBase64(chunkBlob);
        
        const promptFull = `
Eres un asistente experto para profesores de español. Escucharás ${totalChunks > 1 ? `la parte ${i + 1} de ${totalChunks} de ` : ''}la grabación de una clase de español.
Tu tarea es analizar el audio y generar un reporte estructurado para el diario de clases.

${customPrompt.trim() ? `\nOBSERVACIONES Y PEDIDOS ESPECIALES DEL PROFESOR (¡Ten esto muy en cuenta!):\n${customPrompt.trim()}\n` : ''}

Devuelve ÚNICAMENTE un objeto JSON válido con esta estructura exacta (sin bloques de código markdown, solo el JSON puro):
{
  "topicSummary": "Resumen claro de los temas gramaticales, léxicos o culturales.",
  "activities": "Descripción de las dinámicas o actividades hechas en clase.",
  "exercises": "Números de página y ejercicios del libro trabajados. Sé muy preciso."
}
        `;

        const promptExercisesOnly = `
Eres un asistente experto para profesores de español. Escucharás ${totalChunks > 1 ? `la parte ${i + 1} de ${totalChunks} de ` : ''}la grabación de una clase de español.
Tu ÚNICA tarea es extraer los números de página y los ejercicios del libro que se mencionan o trabajan en la clase. Ignora todo lo demás.

${customPrompt.trim() ? `\nOBSERVACIONES Y PEDIDOS ESPECIALES DEL PROFESOR:\n${customPrompt.trim()}\n` : ''}

Devuelve ÚNICAMENTE un objeto JSON válido con esta estructura exacta (sin bloques de código markdown, solo el JSON puro):
{
  "exercises": "Números de página y ejercicios del libro trabajados. Sé muy preciso. Si no hay, indica 'No se mencionaron ejercicios'."
}
        `;

        const prompt = mode === 'exercises_only' ? promptExercisesOnly : promptFull;

        const apiCall = ai.models.generateContent({
          model: 'gemini-3.1-pro-preview',
          contents: [
            {
              inlineData: {
                mimeType: chunkBlob.type || 'audio/webm',
                data: base64Audio
              }
            },
            prompt
          ]
        });

        // 5 minute timeout per chunk
        const timeoutPromise = new Promise((_, reject) => 
          setTimeout(() => reject(new Error('TIMEOUT')), 5 * 60 * 1000)
        );

        const response = await Promise.race([apiCall, timeoutPromise]) as any;
        const text = response.text || '{}';
        const cleanText = text.replace(/\`\`\`json/g, '').replace(/\`\`\`/g, '').trim();
        
        try {
          partialResults.push(JSON.parse(cleanText));
        } catch (e) {
          console.warn(`Failed to parse JSON for chunk ${i+1}`, text);
          partialResults.push({
            topicSummary: text,
            activities: '',
            exercises: ''
          });
        }
      }

      let finalData;
      
      if (totalChunks === 1) {
        finalData = partialResults[0];
      } else {
        setProcessingMessage('Sintetizando resultados finales...');
        
        const synthesisPromptFull = `
Eres un asistente experto. He dividido una clase larga en varias partes y he extraído los resúmenes de cada una.
Aquí tienes los datos parciales en formato JSON:
${JSON.stringify(partialResults, null, 2)}

Tu tarea es unificar esta información en un único reporte final coherente.
Elimina redundancias y crea un resumen fluido.
Devuelve ÚNICAMENTE un objeto JSON válido con esta estructura exacta (sin bloques de código markdown, solo el JSON puro):
{
  "topicSummary": "Resumen unificado y claro de los temas gramaticales, léxicos o culturales de toda la clase.",
  "activities": "Descripción unificada de las dinámicas o actividades hechas en toda la clase.",
  "exercises": "Todos los números de página y ejercicios del libro trabajados, consolidados."
}
        `;

        const synthesisPromptExercisesOnly = `
Eres un asistente experto. He dividido una clase larga en varias partes y he extraído los ejercicios de cada una.
Aquí tienes los datos parciales en formato JSON:
${JSON.stringify(partialResults, null, 2)}

Tu tarea es unificar esta información en un único reporte final.
Devuelve ÚNICAMENTE un objeto JSON válido con esta estructura exacta (sin bloques de código markdown, solo el JSON puro):
{
  "exercises": "Todos los números de página y ejercicios del libro trabajados, consolidados."
}
        `;

        const synthesisPrompt = mode === 'exercises_only' ? synthesisPromptExercisesOnly : synthesisPromptFull;

        const apiCall = ai.models.generateContent({
          model: 'gemini-3.1-pro-preview',
          contents: synthesisPrompt
        });

        const timeoutPromise = new Promise((_, reject) => 
          setTimeout(() => reject(new Error('TIMEOUT')), 2 * 60 * 1000)
        );

        const response = await Promise.race([apiCall, timeoutPromise]) as any;
        const text = response.text || '{}';
        const cleanText = text.replace(/\`\`\`json/g, '').replace(/\`\`\`/g, '').trim();
        
        try {
          finalData = JSON.parse(cleanText);
        } catch (e) {
          console.warn('Failed to parse final synthesis JSON', text);
          finalData = {
            topicSummary: text,
            activities: 'Error al sintetizar actividades.',
            exercises: 'Error al sintetizar ejercicios.'
          };
        }
      }

      let content = '';
      let exercises = '';
      
      if (mode === 'exercises_only') {
        content = `### Ejercicios y páginas del libro\n${finalData.exercises || 'Sin ejercicios'}`;
        exercises = finalData.exercises || 'No se pudieron extraer los ejercicios aislados.';
      } else {
        content = `### Resumen del tema\n${finalData.topicSummary || 'Sin resumen'}\n\n### Actividades realizadas\n${finalData.activities || 'Sin actividades'}\n\n### Ejercicios y páginas del libro\n${finalData.exercises || 'Sin ejercicios'}`;
        exercises = finalData.exercises || 'No se pudieron extraer los ejercicios aislados.';
      }

      const newSummary: ClassSummary = {
        id: Date.now().toString(),
        date: new Date().toISOString(),
        duration: pendingDuration,
        content: content,
        exercises: exercises,
      };

      setSummaries((prev) => [newSummary, ...prev]);
      setExpandedId(newSummary.id); // Auto-expand the new summary
      success = true;
    } catch (err: any) {
      console.error('Error processing audio:', err);
      if (err.message === 'TIMEOUT') {
        setError('TIEMPO DE ESPERA AGOTADO (5 min). La IA tardó demasiado en responder. Reintenta o usa un audio más corto.');
      } else {
        setError(`ERROR DE PROCESAMIENTO: ${err.message || 'Fallo de conexión o archivo muy grande.'}`);
      }
    } finally {
      setIsProcessing(false);
      setProcessingMessage(null);
      releaseWakeLock();
      
      if (success) {
        // Only clear the audio if processing was successful
        setPendingAudioBlob(null);
        setCustomPrompt('');
        setRecordingTime(0);
        setRecoveredState(false);
        
        // Clear persistent storage on success
        clearAudioFromDB();
        localStorage.removeItem('pending_processing_state');
      } else {
        // If it failed, go back to the prompt screen so the user can retry
        setIsAwaitingPrompt(true);
      }
    }
  };

  const confirmDelete = () => {
    if (deleteTarget === 'ALL') {
      setSummaries([]);
      setExpandedId(null);
    } else if (deleteTarget) {
      setSummaries((prev) => prev.filter(s => s.id !== deleteTarget));
      if (expandedId === deleteTarget) setExpandedId(null);
    }
    setDeleteTarget(null);
  };

  const copyToClipboard = (text: string, id: string, type: 'ALL' | 'EX') => {
    navigator.clipboard.writeText(text);
    if (type === 'ALL') {
      setCopiedId(id);
      setTimeout(() => setCopiedId(null), 2000);
    } else {
      setCopiedExId(id);
      setTimeout(() => setCopiedExId(null), 2000);
    }
  };

  const extractExercises = (content: string) => {
    const match = content.match(/(?:Ejercicios y páginas del libro|3\.\s*\*\*Ejercicios)[^\n]*\n([\s\S]*)/i);
    return match ? match[1].trim() : content;
  };

  const filteredSummaries = summaries.filter(summary => {
    const query = searchQuery.toLowerCase();
    return (
      summary.content.toLowerCase().includes(query) ||
      (summary.exercises && summary.exercises.toLowerCase().includes(query))
    );
  });

  return (
    <div className="min-h-screen bg-[#050505] text-neutral-200 font-sans selection:bg-neutral-800 pb-24">
      {/* Header - Brutalist Top Bar */}
      <header className="border-b border-neutral-800 p-6 flex justify-between items-end">
        <div>
          <h1 className="font-display text-2xl md:text-3xl font-bold tracking-tighter uppercase text-white">
            Diario_AI
          </h1>
          <p className="font-mono text-xs text-neutral-500 tracking-widest uppercase mt-1">
            Análisis de Clases
          </p>
        </div>
        <div className="flex items-center gap-4">
          <div className="font-mono text-xs text-neutral-600 uppercase hidden sm:block">
            v1.0.0
          </div>
          <button 
            onClick={() => setShowSettings(true)}
            className="text-neutral-500 hover:text-white transition-colors p-2 border border-transparent hover:border-neutral-800"
            title="Configuración"
          >
            <Settings className="w-5 h-5" />
          </button>
        </div>
      </header>

      <main className="max-w-2xl mx-auto px-6">
        {/* Recording Section */}
        <section className="py-16 flex flex-col items-center justify-center border-b border-neutral-800">
          
          <div className="text-center mb-12">
            {recoveredState && isAwaitingPrompt && (
              <div className="mb-6 inline-flex items-center gap-2 bg-yellow-500/10 text-yellow-500 border border-yellow-500/20 px-4 py-2 rounded-none font-mono text-xs uppercase tracking-wider">
                <AlertCircle className="w-4 h-4" />
                Audio recuperado de una sesión anterior
              </div>
            )}
            <div className="font-mono text-xs text-neutral-500 tracking-[0.2em] uppercase mb-4">
              {isAwaitingPrompt ? 'Esperando instrucciones...' : isRecording ? 'Grabando...' : isProcessing ? 'Procesando...' : 'Estado: Inactivo'}
            </div>
            <div className={`font-mono text-7xl md:text-8xl font-thin tracking-tighter transition-colors duration-500 ${isRecording ? 'text-white' : 'text-neutral-700'}`}>
              {formatTime(isAwaitingPrompt ? pendingDuration : recordingTime)}
            </div>
            {isProcessing && (
              <div className="mt-6 font-mono text-xs text-[#ff3333] animate-pulse uppercase tracking-widest flex flex-col items-center gap-2">
                <span>{processingMessage || 'Procesando...'}</span>
                <span>¡No apagues la pantalla ni cierres la app!</span>
              </div>
            )}
          </div>

          {isAwaitingPrompt ? (
            <div className="w-full max-w-sm animate-in fade-in zoom-in duration-300">
              <label className="block font-mono text-xs text-neutral-400 uppercase tracking-wider mb-2 text-left">
                Observaciones / Pedidos Especiales (Opcional)
              </label>
              <textarea
                value={customPrompt}
                onChange={(e) => setCustomPrompt(e.target.value)}
                placeholder="Ej: Presta especial atención a los ejercicios de la página 42..."
                className="w-full bg-[#050505] border border-neutral-800 text-neutral-200 p-4 font-sans text-sm focus:outline-none focus:border-white transition-colors resize-none h-32 mb-6 placeholder:text-neutral-700"
              />
              <div className="flex flex-col gap-3">
                <button
                  onClick={() => processAudio('full')}
                  className="w-full font-mono text-xs text-black bg-white hover:bg-neutral-200 py-3 uppercase tracking-wider transition-colors font-bold"
                >
                  Procesar Completo
                </button>
                <button
                  onClick={() => processAudio('exercises_only')}
                  className="w-full font-mono text-xs text-neutral-200 bg-neutral-800 hover:bg-neutral-700 border border-neutral-700 py-3 uppercase tracking-wider transition-colors"
                >
                  Solo Ejercicios
                </button>
                <button
                  onClick={cancelProcessing}
                  className="w-full font-mono text-xs text-neutral-400 border border-neutral-800 hover:bg-neutral-900 hover:text-[#ff3333] py-3 uppercase tracking-wider transition-colors"
                >
                  Descartar
                </button>
              </div>
            </div>
          ) : (
            <div className="relative flex justify-center items-center h-32 w-32">
              {isRecording && (
                <div className="absolute inset-0 rounded-full border border-[#ff3333] animate-ping opacity-50"></div>
              )}
              <button
                onClick={isRecording ? stopRecording : startRecording}
                disabled={isProcessing}
                className={`relative z-10 flex items-center justify-center w-24 h-24 rounded-full transition-all duration-300 ${
                  isRecording 
                    ? 'bg-[#ff3333] text-black scale-95' 
                    : isProcessing
                      ? 'bg-neutral-900 text-neutral-600 border border-neutral-800 cursor-not-allowed'
                      : 'bg-transparent border-2 border-neutral-700 text-white hover:bg-white hover:text-black'
                }`}
              >
                {isProcessing ? (
                  <Loader2 className="w-8 h-8 animate-spin" />
                ) : isRecording ? (
                  <Square className="w-8 h-8 fill-current" />
                ) : (
                  <Mic className="w-8 h-8" />
                )}
              </button>
            </div>
          )}

          {error && (
            <div className="mt-8 font-mono text-xs text-[#ff3333] border border-[#ff3333] p-4 uppercase tracking-wider w-full text-center bg-[#ff3333]/10">
              {error}
            </div>
          )}
        </section>

        {/* Summaries List */}
        <section className="pt-12">
          <div className="flex justify-between items-end mb-8">
            <h2 className="font-display text-xl font-bold uppercase tracking-tight text-white">
              Registros
            </h2>
            <div className="flex items-center gap-4">
              <span className="font-mono text-xs text-neutral-500">
                [{summaries.length}] ENTRADAS
              </span>
              {summaries.length > 0 && (
                <button
                  onClick={() => setDeleteTarget('ALL')}
                  className="font-mono text-xs text-[#ff3333] hover:text-white hover:bg-[#ff3333] border border-[#ff3333] px-3 py-1 transition-colors uppercase tracking-wider"
                >
                  Borrar Todo
                </button>
              )}
            </div>
          </div>

          {summaries.length > 0 && (
            <div className="relative mb-8">
              <div className="absolute inset-y-0 left-0 pl-4 flex items-center pointer-events-none">
                <Search className="h-4 w-4 text-neutral-600" />
              </div>
              <input
                type="text"
                placeholder="Buscar en resúmenes y ejercicios..."
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                className="w-full bg-[#050505] border border-neutral-800 text-neutral-200 py-4 pl-12 pr-4 font-sans text-sm focus:outline-none focus:border-white transition-colors placeholder:text-neutral-700"
              />
            </div>
          )}

          {summaries.length === 0 ? (
            <div className="font-mono text-sm text-neutral-600 uppercase tracking-widest border border-neutral-800 p-8 text-center">
              No hay registros en la base de datos.
            </div>
          ) : filteredSummaries.length === 0 ? (
            <div className="font-mono text-sm text-neutral-600 uppercase tracking-widest border border-neutral-800 p-8 text-center">
              No se encontraron resultados para "{searchQuery}"
            </div>
          ) : (
            <div className="space-y-0">
              {filteredSummaries.map((summary) => (
                <article key={summary.id} className="border-t border-neutral-800 group">
                  <header 
                    className="flex justify-between items-center py-6 cursor-pointer hover:bg-neutral-900/50 transition-colors px-4"
                    onClick={() => setExpandedId(expandedId === summary.id ? null : summary.id)}
                  >
                    <div>
                      <div className="font-mono text-xs text-neutral-400 uppercase tracking-wider mb-1">
                        {format(new Date(summary.date), "dd.MM.yyyy", { locale: es })} // {formatTime(summary.duration)}
                      </div>
                      <div className="font-display text-lg text-white font-medium">
                        {format(new Date(summary.date), "EEEE", { locale: es }).toUpperCase()}
                      </div>
                    </div>
                    <div className="text-neutral-500 group-hover:text-white transition-colors">
                      {expandedId === summary.id ? <ChevronUp className="w-6 h-6" /> : <ChevronDown className="w-6 h-6" />}
                    </div>
                  </header>
                  
                  {expandedId === summary.id && (
                    <div className="px-4 pb-8 pt-2 animate-in slide-in-from-top-2 fade-in duration-200">
                      <div className="flex flex-wrap gap-3 mb-6">
                        <button 
                          onClick={(e) => { e.stopPropagation(); copyToClipboard(summary.content, summary.id, 'ALL'); }}
                          className="flex items-center gap-2 font-mono text-xs text-neutral-300 border border-neutral-700 hover:border-white hover:text-white px-3 py-2 uppercase tracking-wider transition-colors"
                        >
                          {copiedId === summary.id ? <Check className="w-4 h-4 text-green-500" /> : <Copy className="w-4 h-4" />}
                          Copiar Todo
                        </button>
                        
                        <button 
                          onClick={(e) => { e.stopPropagation(); copyToClipboard(summary.exercises || extractExercises(summary.content), summary.id, 'EX'); }}
                          className="flex items-center gap-2 font-mono text-xs text-neutral-300 border border-neutral-700 hover:border-white hover:text-white px-3 py-2 uppercase tracking-wider transition-colors"
                        >
                          {copiedExId === summary.id ? <Check className="w-4 h-4 text-green-500" /> : <Copy className="w-4 h-4" />}
                          Copiar Ejercicios
                        </button>

                        <button 
                          onClick={(e) => { e.stopPropagation(); setDeleteTarget(summary.id); }}
                          className="flex items-center gap-2 font-mono text-xs text-[#ff3333] border border-neutral-800 hover:border-[#ff3333] hover:bg-[#ff3333]/10 px-3 py-2 uppercase tracking-wider transition-colors ml-auto"
                        >
                          <Trash2 className="w-4 h-4" />
                          Eliminar
                        </button>
                      </div>

                      <div className="prose prose-invert prose-neutral max-w-none 
                        prose-p:text-neutral-300 prose-p:leading-relaxed 
                        prose-headings:font-display prose-headings:uppercase prose-headings:tracking-tight prose-headings:text-white prose-headings:text-lg prose-headings:mt-6 prose-headings:mb-2
                        prose-strong:text-white prose-strong:font-medium
                        prose-ul:text-neutral-300 prose-li:marker:text-neutral-600
                        prose-a:text-white hover:prose-a:text-neutral-300 bg-neutral-900/30 p-6 border border-neutral-800">
                        <ReactMarkdown>{summary.content}</ReactMarkdown>
                      </div>
                    </div>
                  )}
                </article>
              ))}
            </div>
          )}
        </section>
      </main>

      {/* Delete Confirmation Modal */}
      {deleteTarget && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/80 backdrop-blur-sm p-4">
          <div className="bg-[#050505] border border-neutral-800 p-6 max-w-sm w-full shadow-2xl">
            <h3 className="font-display text-xl font-bold text-white uppercase tracking-tight mb-2">
              {deleteTarget === 'ALL' ? '¿Borrar todos los registros?' : '¿Borrar este registro?'}
            </h3>
            <p className="text-neutral-400 text-sm mb-6">
              Esta acción no se puede deshacer. Los datos se perderán permanentemente.
            </p>
            <div className="flex justify-end gap-3">
              <button
                onClick={() => setDeleteTarget(null)}
                className="font-mono text-xs text-neutral-400 hover:text-white px-4 py-2 uppercase tracking-wider transition-colors"
              >
                Cancelar
              </button>
              <button
                onClick={confirmDelete}
                className="font-mono text-xs text-black bg-[#ff3333] hover:bg-red-600 px-4 py-2 uppercase tracking-wider transition-colors font-bold"
              >
                Confirmar
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Settings Modal */}
      {showSettings && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/80 backdrop-blur-sm p-4">
          <div className="bg-[#050505] border border-neutral-800 p-6 max-w-lg w-full shadow-2xl overflow-y-auto max-h-[90vh]">
            <div className="flex justify-between items-center mb-6">
              <h3 className="font-display text-xl font-bold text-white uppercase tracking-tight">
                Configuración de API
              </h3>
              <button onClick={() => setShowSettings(false)} className="text-neutral-500 hover:text-white transition-colors">
                <X className="w-6 h-6" />
              </button>
            </div>

            <div className="prose prose-invert prose-sm max-w-none mb-8 text-neutral-400">
              <h4 className="text-white font-mono uppercase tracking-wider text-xs mb-3 border-b border-neutral-800 pb-2">Paso 1: Conseguir la API Key</h4>
              <ol className="list-decimal pl-4 mb-6 space-y-2">
                <li>Ve a <a href="https://aistudio.google.com/app/apikey" target="_blank" rel="noreferrer" className="text-white underline hover:text-neutral-300">Google AI Studio</a>.</li>
                <li>Inicia sesión con tu cuenta de Google.</li>
                <li>Haz clic en el botón azul <strong>"Create API Key"</strong>.</li>
                <li>Copia la clave generada (es un texto largo que empieza con "AIza...").</li>
              </ol>

              <h4 className="text-white font-mono uppercase tracking-wider text-xs mb-3 border-b border-neutral-800 pb-2">Paso 2: Guardar la clave</h4>
              <p className="mb-2">
                <strong>Opción A (Aquí):</strong> Pégala abajo. Se guardará solo en este navegador (ideal para uso personal).<br/>
                <strong>Opción B (Vercel):</strong> Ve a tu proyecto en Vercel &gt; Settings &gt; Environment Variables. Añade <code>GEMINI_API_KEY</code> y pega tu clave.
              </p>
            </div>

            <div className="mb-8">
              <label className="block font-mono text-xs text-neutral-400 uppercase tracking-wider mb-2 text-left">
                Tu Gemini API Key Local
              </label>
              <input
                type="password"
                value={localApiKey}
                onChange={(e) => setLocalApiKey(e.target.value)}
                placeholder="AIzaSy..."
                className="w-full bg-[#0a0a0a] border border-neutral-800 text-white p-4 font-mono text-sm focus:outline-none focus:border-white transition-colors placeholder:text-neutral-700"
              />
            </div>

            <div className="flex justify-end gap-3">
              <button
                onClick={() => {
                  setLocalApiKey('');
                  localStorage.removeItem('GEMINI_API_KEY');
                }}
                className="font-mono text-xs text-[#ff3333] border border-neutral-800 hover:border-[#ff3333] hover:bg-[#ff3333]/10 px-4 py-3 uppercase tracking-wider transition-colors"
              >
                Borrar Key
              </button>
              <button
                onClick={() => {
                  if (localApiKey.trim()) {
                    localStorage.setItem('GEMINI_API_KEY', localApiKey.trim());
                  }
                  setShowSettings(false);
                }}
                className="font-mono text-xs text-black bg-white hover:bg-neutral-200 px-4 py-3 uppercase tracking-wider transition-colors font-bold"
              >
                Guardar
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
