import React, { useEffect, useState, useRef } from 'react';
import {
  View, Text, TouchableOpacity, StyleSheet,
  NativeModules, NativeEventEmitter, PermissionsAndroid,
  ScrollView, Animated, StatusBar, ActivityIndicator,
} from 'react-native';
import Sound from 'react-native-sound';

Sound.setCategory('Playback');

const { Vosk } = NativeModules;
const voskEmitter = new NativeEventEmitter(Vosk);

// ── Audio samples with ground truth ───────────────────────────────────────────
const SAMPLES = [
  {
    id: 'utt_0000000',
    url: 'https://drive.google.com/uc?export=download&id=1FGM-xOW4dnpW7AnqhzuEwk_qy0iktc_E',
    groundTruth: 'ಇಂದು ಬೆಳಗ್ಗೆ ಶ್ವೇತಾ ತನ್ನ ಗೆಳೆಯ ಅಮಿತ್ನನ್ನು ಭೇಟಿ ಮಾಡಲು ಹೋಟೆಲ್ಗೆ ಬಂದಿದ್ದಳು',
  },
  {
    id: 'utt_0000001',
    url: 'https://drive.google.com/uc?export=download&id=1n_F_TSXiUDBXoFYeFV96d7sTbLotz0zH',
    groundTruth: 'ತನ್ನ ಕುಟುಂಬವೇ ಒಂದು ಭಾರತ ಎನ್ನುವುದನ್ನು ಮನದಲ್ಲಿಟ್ಟುಕೊಂಡು ಅವರು ಜನರನ್ನು ತಲುಪಬೇಕಾಗಿದೆ',
  },
];

// ── Word diff ─────────────────────────────────────────────────────────────────
function wordDiff(ref: string, hyp: string) {
  const r = ref.trim().split(/\s+/);
  const h = hyp.trim().split(/\s+/);
  const len = Math.max(r.length, h.length);
  return Array.from({ length: len }, (_, i) => {
    if (!r[i]) return { word: h[i], status: 'extra' as const };
    if (!h[i]) return { word: r[i], status: 'missing' as const };
    return { word: h[i], status: r[i] === h[i] ? 'ok' as const : 'wrong' as const };
  });
}

// ── Sample card ───────────────────────────────────────────────────────────────
function SampleCard({ sample, modelReady }: { sample: typeof SAMPLES[0]; modelReady: boolean }) {
  const [playing, setPlaying]       = useState(false);
  const [transcribing, setT]        = useState(false);
  const [status, setStatus]         = useState('');
  const [voskText, setVoskText]     = useState('');
  const soundRef = useRef<Sound | null>(null);

  useEffect(() => {
    const s1 = voskEmitter.addListener('onFinalResult', e => {
      if (transcribing) {
        setVoskText(e.text || '');
        setT(false);
        setStatus('');
      }
    });
    const s2 = voskEmitter.addListener('onStatus', e => {
      if (transcribing) setStatus(e.text || '');
    });
    return () => { s1.remove(); s2.remove(); };
  }, [transcribing]);

  const play = () => {
    soundRef.current?.stop(); soundRef.current?.release();
    setPlaying(true);
    setStatus('Loading audio…');
    const snd = new Sound(sample.url, '', err => {
      if (err) { setPlaying(false); setStatus('Play failed: ' + err.message); return; }
      setStatus('Playing…');
      soundRef.current = snd;
      snd.play(ok => {
        setPlaying(false);
        setStatus(ok ? '' : 'Playback error');
        soundRef.current = null;
      });
    });
  };

  const stop = () => {
    soundRef.current?.stop(); soundRef.current?.release();
    soundRef.current = null; setPlaying(false); setStatus('');
  };

  const sendToVosk = async () => {
    if (!modelReady || transcribing) return;
    stop(); // stop any playback first
    setT(true); setVoskText(''); setStatus('Starting…');
    try {
      await Vosk.transcribeUrl(sample.url);
    } catch (e: any) {
      setStatus('Error: ' + e.message); setT(false);
    }
  };

  const diff = voskText ? wordDiff(sample.groundTruth, voskText) : null;
  const wer  = diff
    ? Math.round(diff.filter(d => d.status !== 'ok').length /
        sample.groundTruth.trim().split(/\s+/).length * 100)
    : null;

  return (
    <View style={c.card}>
      {/* ID + WER */}
      <View style={c.row}>
        <Text style={c.id}>{sample.id}</Text>
        {wer !== null && (
          <View style={[c.badge, { backgroundColor: wer < 20 ? '#0c2e1a' : '#2e0c0c' }]}>
            <Text style={[c.badgeTxt, { color: wer < 20 ? '#4ade80' : '#f87171' }]}>WER {wer}%</Text>
          </View>
        )}
      </View>

      {/* Reference */}
      <Text style={c.label}>REFERENCE</Text>
      <Text style={c.ref}>{sample.groundTruth}</Text>

      {/* Vosk output */}
      <Text style={[c.label, { marginTop: 10 }]}>VOSK OUTPUT</Text>
      {voskText ? (
        <View style={c.diffRow}>
          {diff!.map((d, i) => (
            <Text key={i} style={[c.word,
              d.status === 'ok'      && c.wOk,
              d.status === 'wrong'   && c.wBad,
              d.status === 'missing' && c.wMiss,
              d.status === 'extra'   && c.wExtra,
            ]}>{d.word} </Text>
          ))}
        </View>
      ) : (
        <Text style={c.empty}>{transcribing ? status || 'Transcribing…' : '—'}</Text>
      )}
      {transcribing && <ActivityIndicator size="small" color="#6C8EFF" style={{ marginTop: 4 }} />}

      {/* Status line */}
      {!transcribing && status ? <Text style={c.status}>{status}</Text> : null}

      {/* Buttons */}
      <View style={[c.row, { marginTop: 12, gap: 8 }]}>
        <TouchableOpacity
          style={[c.btn, c.btnPlay, transcribing && c.dim]}
          onPress={playing ? stop : play}
          disabled={transcribing}
        >
          <Text style={c.btnTxt}>{playing ? '⏹  Stop' : '▶  Play'}</Text>
        </TouchableOpacity>

        <TouchableOpacity
          style={[c.btn, c.btnSend, (!modelReady || transcribing) && c.dim]}
          onPress={sendToVosk}
          disabled={!modelReady || transcribing}
        >
          {transcribing
            ? <ActivityIndicator size="small" color="#6C8EFF" />
            : <Text style={c.btnTxt}>⚡  Send to Vosk</Text>}
        </TouchableOpacity>
      </View>

      {/* Legend */}
      {voskText && (
        <View style={[c.row, { marginTop: 8, gap: 14 }]}>
          {[['#4ade80','Correct'],['#f87171','Wrong'],['#fbbf24','Missing'],['#a78bfa','Extra']].map(([col, lbl]) => (
            <View key={lbl} style={c.legendItem}>
              <View style={[c.dot, { backgroundColor: col }]} />
              <Text style={c.legendTxt}>{lbl}</Text>
            </View>
          ))}
        </View>
      )}
    </View>
  );
}

// ── Main App ──────────────────────────────────────────────────────────────────
type AppState = 'idle' | 'recording' | 'processing';

export default function App() {
  const [modelReady, setModelReady] = useState(false);
  const [appState, setAppState]     = useState<AppState>('idle');
  const [transcript, setTranscript] = useState('');
  const [status, setStatus]         = useState('');
  const [tab, setTab]               = useState<'mic' | 'samples'>('mic');
  const pulseAnim = useRef(new Animated.Value(1)).current;

  useEffect(() => {
    (async () => {
      await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.RECORD_AUDIO);
      try { await Vosk.loadModel(); setModelReady(true); } catch (e) { console.error(e); }
    })();
  }, []);

  useEffect(() => {
    if (appState === 'recording') {
      const loop = Animated.loop(Animated.sequence([
        Animated.timing(pulseAnim, { toValue: 1.4, duration: 600, useNativeDriver: true }),
        Animated.timing(pulseAnim, { toValue: 1,   duration: 600, useNativeDriver: true }),
      ]));
      loop.start(); return () => loop.stop();
    } else pulseAnim.setValue(1);
  }, [appState]);

  useEffect(() => {
    const s1 = voskEmitter.addListener('onFinalResult', e => {
      if (tab === 'mic') { setTranscript(e.text || ''); setAppState('idle'); setStatus(''); }
    });
    const s2 = voskEmitter.addListener('onPartialResult', e => {
      if (tab === 'mic') setTranscript(e.text || '');
    });
    const s3 = voskEmitter.addListener('onStatus', e => {
      if (tab === 'mic') setStatus(e.text || '');
    });
    return () => { s1.remove(); s2.remove(); s3.remove(); };
  }, [tab]);

  const handleMic = async () => {
    if (appState === 'idle') {
      setTranscript(''); setStatus('');
      await Vosk.startListening();
      setAppState('recording');
    } else if (appState === 'recording') {
      setAppState('processing');
      setStatus('Transcribing…');
      await Vosk.stopListening();
    }
  };

  const busy = appState !== 'idle';

  return (
    <View style={s.root}>
      <StatusBar barStyle="light-content" backgroundColor="#090C14" />

      {/* Header */}
      <View style={s.header}>
        <Text style={s.title}>VoiceScribe</Text>
        <View style={[s.pill, { backgroundColor: modelReady ? '#0c2e1a' : '#221e08' }]}>
          <View style={[s.dot, { backgroundColor: modelReady ? '#4ade80' : '#facc15' }]} />
          <Text style={[s.pillTxt, { color: modelReady ? '#4ade80' : '#facc15' }]}>
            {modelReady ? 'Ready' : 'Loading…'}
          </Text>
        </View>
      </View>

      {/* Tabs */}
      <View style={s.tabs}>
        {(['mic','samples'] as const).map(t => (
          <TouchableOpacity key={t} style={[s.tab, tab === t && s.tabActive]} onPress={() => setTab(t)}>
            <Text style={[s.tabTxt, tab === t && s.tabTxtActive]}>
              {t === 'mic' ? '🎙 Microphone' : '🔊 Audio Samples'}
            </Text>
          </TouchableOpacity>
        ))}
      </View>

      {/* ── Mic tab ── */}
      {tab === 'mic' && (
        <>
          <ScrollView style={s.box} contentContainerStyle={s.boxPad}>
            <Text style={transcript ? s.txText : s.hint}>
              {transcript || (appState === 'recording' ? 'Listening…' : 'Tap mic to start')}
            </Text>
          </ScrollView>

          {(appState === 'processing') && (
            <View style={s.statusRow}>
              <ActivityIndicator size="small" color="#6C8EFF" />
              <Text style={s.statusTxt}>{status || 'Transcribing…'}</Text>
            </View>
          )}

          <View style={s.controls}>
            <TouchableOpacity style={[s.sideBtn, busy && s.dim]}
              onPress={() => { setTranscript(''); setStatus(''); }} disabled={busy}>
              <Text style={s.sideTxt}>Clear</Text>
            </TouchableOpacity>

            <View style={s.micArea}>
              {appState === 'recording' && (
                <Animated.View style={[s.ripple, { transform: [{ scale: pulseAnim }] }]} />
              )}
              <TouchableOpacity
                style={[s.micBtn,
                  appState === 'recording'  && s.micStop,
                  appState === 'processing' && s.micBusy,
                  (!modelReady || appState === 'processing') && s.dim,
                ]}
                onPress={handleMic}
                disabled={!modelReady || appState === 'processing'}
              >
                {appState === 'processing'
                  ? <ActivityIndicator color="#fff" size="small" />
                  : <Text style={s.micIcon}>{appState === 'recording' ? '⏹' : '🎙'}</Text>}
              </TouchableOpacity>
            </View>

            <View style={s.sideBtn} />
          </View>
        </>
      )}

      {/* ── Samples tab ── */}
      {tab === 'samples' && (
        <ScrollView style={{ flex: 1 }} contentContainerStyle={{ padding: 14, paddingBottom: 40 }}>
          <Text style={s.samplesTitle}>Kannada ASR — Sample Evaluation</Text>
          <Text style={s.samplesSub}>Download each audio, send to Vosk, compare with reference.</Text>
          {SAMPLES.map(s2 => (
            <SampleCard key={s2.id} sample={s2} modelReady={modelReady} />
          ))}
        </ScrollView>
      )}
    </View>
  );
}

// ── Styles ────────────────────────────────────────────────────────────────────
const s = StyleSheet.create({
  root:         { flex: 1, backgroundColor: '#090C14', paddingTop: 48 },
  header:       { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: 16, marginBottom: 8 },
  title:        { fontSize: 22, fontWeight: '700', color: '#EEF2FF', letterSpacing: -0.5 },
  pill:         { flexDirection: 'row', alignItems: 'center', gap: 5, paddingHorizontal: 10, paddingVertical: 5, borderRadius: 20 },
  dot:          { width: 7, height: 7, borderRadius: 4 },
  pillTxt:      { fontSize: 11, fontWeight: '600' },
  tabs:         { flexDirection: 'row', marginHorizontal: 14, marginBottom: 10, backgroundColor: '#10131D', borderRadius: 12, padding: 4, borderWidth: 1, borderColor: '#1C2035' },
  tab:          { flex: 1, paddingVertical: 8, borderRadius: 9, alignItems: 'center' },
  tabActive:    { backgroundColor: '#1C2540' },
  tabTxt:       { color: '#3A4060', fontSize: 12, fontWeight: '600' },
  tabTxtActive: { color: '#6C8EFF' },
  box:          { flex: 1, marginHorizontal: 14, backgroundColor: '#10131D', borderRadius: 16, borderWidth: 1, borderColor: '#1C2035' },
  boxPad:       { padding: 18, minHeight: 120 },
  hint:         { color: '#30364A', fontSize: 15, lineHeight: 24, textAlign: 'center', marginTop: 20 },
  txText:       { color: '#D4DBF5', fontSize: 18, lineHeight: 30 },
  statusRow:    { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 8, marginTop: 6 },
  statusTxt:    { color: '#6C8EFF', fontSize: 12 },
  controls:     { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: 28, paddingVertical: 14 },
  micArea:      { width: 76, height: 76, alignItems: 'center', justifyContent: 'center' },
  ripple:       { position: 'absolute', width: 76, height: 76, borderRadius: 38, backgroundColor: '#EF4444', opacity: 0.2 },
  micBtn:       { width: 64, height: 64, borderRadius: 32, backgroundColor: '#3B54F0', alignItems: 'center', justifyContent: 'center', elevation: 8 },
  micStop:      { backgroundColor: '#EF4444' },
  micBusy:      { backgroundColor: '#333' },
  micIcon:      { fontSize: 26 },
  sideBtn:      { width: 72, height: 38, borderRadius: 10, backgroundColor: '#141726', alignItems: 'center', justifyContent: 'center' },
  sideTxt:      { color: '#5A6180', fontSize: 13, fontWeight: '500' },
  dim:          { opacity: 0.3 },
  samplesTitle: { color: '#EEF2FF', fontSize: 15, fontWeight: '700', marginBottom: 4 },
  samplesSub:   { color: '#3A4060', fontSize: 11, lineHeight: 17, marginBottom: 14 },
});

const c = StyleSheet.create({
  card:      { backgroundColor: '#10131D', borderRadius: 16, borderWidth: 1, borderColor: '#1C2035', padding: 14, marginBottom: 14 },
  row:       { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  id:        { color: '#3A4060', fontSize: 10, fontFamily: 'monospace', letterSpacing: 1 },
  badge:     { paddingHorizontal: 8, paddingVertical: 3, borderRadius: 8 },
  badgeTxt:  { fontSize: 11, fontWeight: '700' },
  label:     { color: '#2A3050', fontSize: 9, fontWeight: '700', letterSpacing: 1.5, textTransform: 'uppercase', marginTop: 10, marginBottom: 4 },
  ref:       { color: '#6C8EFF', fontSize: 14, lineHeight: 22 },
  diffRow:   { flexDirection: 'row', flexWrap: 'wrap' },
  word:      { fontSize: 14, lineHeight: 24 },
  wOk:       { color: '#4ade80' },
  wBad:      { color: '#f87171', textDecorationLine: 'underline' },
  wMiss:     { color: '#fbbf24', textDecorationLine: 'line-through' },
  wExtra:    { color: '#a78bfa' },
  empty:     { color: '#30364A', fontSize: 13, fontStyle: 'italic' },
  status:    { color: '#6C8EFF', fontSize: 11, marginTop: 4 },
  btn:       { flex: 1, paddingVertical: 10, borderRadius: 10, alignItems: 'center', justifyContent: 'center' },
  btnPlay:   { backgroundColor: '#141726', borderWidth: 1, borderColor: '#1C2035' },
  btnSend:   { backgroundColor: '#1C2540', borderWidth: 1, borderColor: '#2A3580' },
  btnTxt:    { color: '#8890AA', fontSize: 12, fontWeight: '600' },
  dim:       { opacity: 0.35 },
  legendItem:{ flexDirection: 'row', alignItems: 'center', gap: 4 },
  dot:       { width: 7, height: 7, borderRadius: 4 },
  legendTxt: { color: '#3A4060', fontSize: 10 },
});