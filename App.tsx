import React, { useEffect, useState, useRef } from 'react';
import {
  View, Text, TouchableOpacity, StyleSheet,
  NativeModules, NativeEventEmitter, PermissionsAndroid,
  ScrollView, Animated, StatusBar, ActivityIndicator,
} from 'react-native';

const { Vosk } = NativeModules;
const voskEmitter = new NativeEventEmitter(Vosk);

type AppState = 'idle' | 'recording' | 'processing' | 'diagnosing';

export default function App() {
  const [modelReady, setModelReady]   = useState(false);
  const [appState, setAppState]       = useState<AppState>('idle');
  const [transcript, setTranscript]   = useState('');
  const [diagnostic, setDiagnostic]   = useState('');
  const [showDiag, setShowDiag]       = useState(false);
  const [status, setStatus]           = useState('');

  const [globalGain, setGlobalGain]   = useState(1.5);
  const [numPasses, setNumPasses]     = useState(3);

  const scrollRef = useRef<ScrollView>(null);
  const pulseAnim = useRef(new Animated.Value(1)).current;

  useEffect(() => {
    (async () => {
      await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.RECORD_AUDIO);
      try { await Vosk.loadModel(); setModelReady(true); }
      catch (e) { console.error(e); }
    })();
  }, []);

  useEffect(() => {
    if (appState === 'recording') {
      const loop = Animated.loop(Animated.sequence([
        Animated.timing(pulseAnim, { toValue: 1.4, duration: 600, useNativeDriver: true }),
        Animated.timing(pulseAnim, { toValue: 1,   duration: 600, useNativeDriver: true }),
      ]));
      loop.start();
      return () => loop.stop();
    } else {
      pulseAnim.setValue(1);
    }
  }, [appState]);

  useEffect(() => {
    const subs = [
      voskEmitter.addListener('onPartialResult', () => {}),
      voskEmitter.addListener('onFinalResult', e => {
        if (e.text) setTranscript(e.text);
      }),
      voskEmitter.addListener('onStatus', e => setStatus(e.text || '')),
      voskEmitter.addListener('onDiagnostic', e => {
        setDiagnostic(e.text || '');
        setShowDiag(true);
        setAppState('idle');
      }),
    ];
    return () => subs.forEach(s => s.remove());
  }, []);

  const applyConfig = async () => {
    await Vosk.setConfig({
      globalGain,
      numPasses,
    });
  };

  useEffect(() => {
    if (modelReady) applyConfig();
  }, [globalGain, numPasses, modelReady]);

  const handleMic = async () => {
    if (appState === 'idle') {
      await Vosk.startListening();
      setAppState('recording');
    } else if (appState === 'recording') {
      setAppState('processing');
      await Vosk.stopListening();
      setAppState('idle');
    }
  };

  const handleDiagnose = async () => {
    setAppState('diagnosing');
    setDiagnostic('');
    setShowDiag(false);
    await Vosk.diagnose();
  };

  const handleClear = async () => {
    if (appState === 'recording') await Vosk.stopListening().catch(() => {});
    setAppState('idle');
    setTranscript('');
  };

  const busy = appState !== 'idle';

  return (
    <View style={s.root}>
      <StatusBar barStyle="light-content" backgroundColor="#090C14" />
      <View style={s.header}>
        <Text style={s.title}>VoiceScribe</Text>
        <View style={[s.pill, { backgroundColor: modelReady ? '#0c2e1a' : '#221e08' }]}>
          <View style={[s.dot, { backgroundColor: modelReady ? '#4ade80' : '#facc15' }]} />
          <Text style={[s.pillTxt, { color: modelReady ? '#4ade80' : '#facc15' }]}>
            {modelReady ? 'Ready' : 'Loading…'}
          </Text>
        </View>
      </View>

      {showDiag && (
        <View style={s.diagBox}>
          <Text style={s.diagTitle}>Diagnostic Report</Text>
          <ScrollView style={{ maxHeight: 180 }}>
            <Text style={s.diagText}>{diagnostic}</Text>
          </ScrollView>
          <TouchableOpacity style={s.closeBtn} onPress={() => setShowDiag(false)}>
            <Text style={s.closeBtnTxt}>Close</Text>
          </TouchableOpacity>
        </View>
      )}

      <ScrollView ref={scrollRef} style={s.box} contentContainerStyle={s.boxPad}>
        {transcript.length === 0 ? (
          <Text style={s.hint}>
            {appState === 'diagnosing'
              ? '🎙 Speak normally for 5 seconds…'
              : `Tap mic → speak → stop → ${numPasses} passes\nGain ${globalGain}x`}
          </Text>
        ) : (
          <Text style={s.txText}>{transcript}</Text>
        )}
      </ScrollView>

      {(appState === 'processing' || appState === 'diagnosing') && (
        <View style={s.statusRow}>
          <ActivityIndicator size="small" color="#6C8EFF" />
          <Text style={s.statusTxt}>{status || 'Processing…'}</Text>
        </View>
      )}

      <View style={s.controls}>
        <TouchableOpacity style={[s.sideBtn, busy && s.dim]} onPress={handleClear} disabled={busy}>
          <Text style={s.sideTxt}>Clear</Text>
        </TouchableOpacity>

        <View style={s.micArea}>
          {appState === 'recording' && (
            <Animated.View style={[s.ripple, { transform: [{ scale: pulseAnim }] }]} />
          )}
          <TouchableOpacity
            style={[
              s.micBtn,
              appState === 'recording'  && s.micStop,
              appState === 'processing' && s.micBusy,
              (!modelReady || (busy && appState !== 'recording')) && s.dim,
            ]}
            onPress={handleMic}
            disabled={!modelReady || (busy && appState !== 'recording')}
          >
            {appState === 'processing'
              ? <ActivityIndicator color="#fff" size="small" />
              : <Text style={s.micIcon}>{appState === 'recording' ? '⏹' : '🎙'}</Text>}
          </TouchableOpacity>
        </View>

        <TouchableOpacity style={[s.sideBtn, busy && s.dim]} onPress={handleDiagnose} disabled={busy}>
          <Text style={s.sideTxt}>Diagnose</Text>
        </TouchableOpacity>
      </View>

      <ScrollView style={s.cfgPanel} contentContainerStyle={s.cfgPad}>
        <Text style={s.cfgTitle}>Configuration</Text>

        <Row label={`Recognition passes  ${numPasses}`}>
          <StepBtn onPress={() => setNumPasses(v => Math.max(1, v - 1))} label="−" />
          <StepBtn onPress={() => setNumPasses(v => Math.min(10, v + 1))} label="+" />
        </Row>

        <Row label={`Global Gain  ×${globalGain.toFixed(1)}`}>
          <StepBtn onPress={() => setGlobalGain(v => Math.max(0.5, v - 0.5))} label="−" />
          <StepBtn onPress={() => setGlobalGain(v => Math.min(5, v + 0.5))} label="+" />
        </Row>
      </ScrollView>
    </View>
  );
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <View style={s.row}>
      <Text style={s.rowLabel}>{label}</Text>
      <View style={s.rowRight}>{children}</View>
    </View>
  );
}

function StepBtn({ label, onPress }: { label: string; onPress: () => void }) {
  return (
    <TouchableOpacity style={s.stepBtn} onPress={onPress}>
      <Text style={s.stepBtnTxt}>{label}</Text>
    </TouchableOpacity>
  );
}

const s = StyleSheet.create({
  root:       { flex: 1, backgroundColor: '#090C14', paddingTop: 48 },
  header:     { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: 16, marginBottom: 10 },
  title:      { fontSize: 22, fontWeight: '700', color: '#EEF2FF', letterSpacing: -0.5 },
  pill:       { flexDirection: 'row', alignItems: 'center', gap: 5, paddingHorizontal: 10, paddingVertical: 5, borderRadius: 20 },
  dot:        { width: 7, height: 7, borderRadius: 4 },
  pillTxt:    { fontSize: 11, fontWeight: '600' },

  diagBox:    { marginHorizontal: 14, marginBottom: 8, backgroundColor: '#0f1a10', borderRadius: 12, borderWidth: 1, borderColor: '#1a3a1a', padding: 12 },
  diagTitle:  { color: '#4ade80', fontSize: 13, fontWeight: '700', marginBottom: 6 },
  diagText:   { color: '#8aaf8a', fontSize: 11, fontFamily: 'monospace', lineHeight: 18 },
  closeBtn:   { marginTop: 8, backgroundColor: '#1a3a1a', borderRadius: 8, padding: 8, alignItems: 'center' },
  closeBtnTxt:{ color: '#4ade80', fontSize: 12, fontWeight: '600' },

  box:        { flex: 1, marginHorizontal: 14, backgroundColor: '#10131D', borderRadius: 16, borderWidth: 1, borderColor: '#1C2035' },
  boxPad:     { padding: 18, minHeight: 120 },
  hint:       { color: '#30364A', fontSize: 15, lineHeight: 24, textAlign: 'center', marginTop: 20 },
  txText:     { color: '#D4DBF5', fontSize: 18, lineHeight: 30 },

  statusRow:  { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 8, marginTop: 6 },
  statusTxt:  { color: '#6C8EFF', fontSize: 12 },

  controls:   { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: 28, paddingVertical: 10 },
  micArea:    { width: 76, height: 76, alignItems: 'center', justifyContent: 'center' },
  ripple:     { position: 'absolute', width: 76, height: 76, borderRadius: 38, backgroundColor: '#EF4444', opacity: 0.2 },
  micBtn:     { width: 64, height: 64, borderRadius: 32, backgroundColor: '#3B54F0', alignItems: 'center', justifyContent: 'center', elevation: 8 },
  micStop:    { backgroundColor: '#EF4444' },
  micBusy:    { backgroundColor: '#222' },
  micIcon:    { fontSize: 26 },
  sideBtn:    { width: 72, height: 38, borderRadius: 10, backgroundColor: '#141726', alignItems: 'center', justifyContent: 'center' },
  sideTxt:    { color: '#5A6180', fontSize: 13, fontWeight: '500' },
  dim:        { opacity: 0.3 },

  cfgPanel:   { maxHeight: 150, borderTopWidth: 1, borderTopColor: '#141726' },
  cfgPad:     { padding: 14, paddingBottom: 24 },
  cfgTitle:   { color: '#3A4060', fontSize: 11, fontWeight: '700', letterSpacing: 1, textTransform: 'uppercase', marginBottom: 8 },
  row:        { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingVertical: 6, borderBottomWidth: 1, borderBottomColor: '#12151E' },
  rowLabel:   { color: '#8890AA', fontSize: 12 },
  rowRight:   { flexDirection: 'row', alignItems: 'center', gap: 6 },
  stepBtn:    { width: 30, height: 30, borderRadius: 8, backgroundColor: '#1A1E2E', alignItems: 'center', justifyContent: 'center' },
  stepBtnTxt: { color: '#8890AA', fontSize: 16, fontWeight: '600' },
});