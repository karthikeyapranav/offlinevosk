import React, { useEffect, useState } from 'react';
import {
  View,
  Text,
  TouchableOpacity,
  StyleSheet,
  NativeModules,
  NativeEventEmitter,
  PermissionsAndroid,
  ScrollView,
} from 'react-native';

const { Vosk } = NativeModules;
const voskEmitter = new NativeEventEmitter(Vosk);

export default function App() {
  const [modelReady, setModelReady] = useState(false);
  const [isRecording, setIsRecording] = useState(false);
  const [partial, setPartial] = useState('');
  const [finalText, setFinalText] = useState('');

  // Load model
  useEffect(() => {
    const init = async () => {
      await PermissionsAndroid.request(
        PermissionsAndroid.PERMISSIONS.RECORD_AUDIO,
      );

      await Vosk.loadModel();
      setModelReady(true);
    };

    init();
  }, []);

  // Event listeners
  useEffect(() => {
    const partialSub = voskEmitter.addListener(
      'onPartialResult',
      e => setPartial(e.text),
    );

    const finalSub = voskEmitter.addListener(
      'onFinalResult',
      e => {
        if (e.text) {
          setFinalText(prev => prev + ' ' + e.text);
          setPartial('');
        }
      },
    );

    return () => {
      partialSub.remove();
      finalSub.remove();
    };
  }, []);

  const start = async () => {
    await Vosk.startListening();
    setIsRecording(true);
  };

  const stop = async () => {
    await Vosk.stopListening();
    setIsRecording(false);
  };

  const clear = () => {
    setFinalText('');
    setPartial('');
  };

  return (
    <View style={styles.container}>
      <Text style={styles.title}>🎙 Live Speech to Text</Text>

      <View style={styles.statusBox}>
        <Text>
          Model: {modelReady ? '✅ Ready' : '⏳ Loading...'}
        </Text>
        <Text>
          Mic: {isRecording ? '🔴 Listening...' : '⚪ Idle'}
        </Text>
      </View>

      <ScrollView style={styles.transcriptBox}>
        <Text style={styles.finalText}>{finalText}</Text>
        {partial ? (
          <Text style={styles.partialText}>{partial}</Text>
        ) : null}
      </ScrollView>

      <View style={styles.buttons}>
        {!isRecording ? (
          <Button label="START" color="#4CAF50" onPress={start} />
        ) : (
          <Button label="STOP" color="#E53935" onPress={stop} />
        )}
        <Button label="CLEAR" color="#2196F3" onPress={clear} />
      </View>
    </View>
  );
}

function Button({ label, onPress, color }) {
  return (
    <TouchableOpacity
      onPress={onPress}
      style={[styles.button, { backgroundColor: color }]}
    >
      <Text style={styles.buttonText}>{label}</Text>
    </TouchableOpacity>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    padding: 20,
    backgroundColor: '#F5F7FA',
  },
  title: {
    fontSize: 24,
    fontWeight: 'bold',
    textAlign: 'center',
    marginBottom: 20,
  },
  statusBox: {
    backgroundColor: '#FFF',
    padding: 15,
    borderRadius: 12,
    marginBottom: 15,
    elevation: 3,
  },
  transcriptBox: {
    flex: 1,
    backgroundColor: '#FFF',
    padding: 15,
    borderRadius: 12,
    elevation: 3,
    marginBottom: 20,
  },
  finalText: {
    fontSize: 18,
    color: '#111',
  },
  partialText: {
    fontSize: 18,
    color: '#888',
    fontStyle: 'italic',
    marginTop: 8,
  },
  buttons: {
    flexDirection: 'row',
    justifyContent: 'space-between',
  },
  button: {
    flex: 1,
    paddingVertical: 15,
    borderRadius: 10,
    marginHorizontal: 5,
  },
  buttonText: {
    color: '#FFF',
    textAlign: 'center',
    fontWeight: 'bold',
  },
});