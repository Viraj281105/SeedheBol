import 'package:flutter/material.dart';
import 'theme.dart';

enum Kind { choice, speak, build, match }

class Exercise {
  final Kind kind;
  final String prompt;      // instruction shown above the exercise
  final String marathi;     // the target phrase in Devanagari
  final String roman;       // transliteration, for a learner who cannot read yet
  final String english;
  final List<String> options;
  final int answer;         // index into options, for choice
  final List<String> bank;  // scrambled tokens, for build
  final List<List<String>> pairs; // [marathi, english], for match

  const Exercise({
    required this.kind,
    required this.prompt,
    this.marathi = '',
    this.roman = '',
    this.english = '',
    this.options = const [],
    this.answer = 0,
    this.bank = const [],
    this.pairs = const [],
  });
}

class Lesson {
  final String title;
  final IconData icon;
  final List<Exercise> exercises;
  const Lesson(this.title, this.icon, this.exercises);
}

class Unit {
  final String title;
  final String subtitle;
  final Gradient gradient;
  final List<Lesson> lessons;
  const Unit(this.title, this.subtitle, this.gradient, this.lessons);
}

/// Sample curriculum. Deliberately not tourist vocabulary — these are the
/// phrases the README's construction worker actually needs in week one.
final units = <Unit>[
  Unit('पहिली भेट', 'First meeting', Desi.gold, [
    Lesson('Greetings', Icons.waving_hand_rounded, [
      const Exercise(
        kind: Kind.choice,
        prompt: 'What does this mean?',
        marathi: 'नमस्कार',
        roman: 'namaskār',
        options: ['Hello', 'Goodbye', 'Thank you', 'Sorry'],
        answer: 0,
      ),
      const Exercise(
        kind: Kind.speak,
        prompt: 'Say it out loud',
        marathi: 'नमस्कार',
        roman: 'namaskār',
        english: 'Hello',
      ),
      const Exercise(
        kind: Kind.match,
        prompt: 'Tap the matching pairs',
        pairs: [
          ['नमस्कार', 'Hello'],
          ['धन्यवाद', 'Thank you'],
          ['होय', 'Yes'],
          ['नाही', 'No'],
        ],
      ),
      const Exercise(
        kind: Kind.speak,
        prompt: 'Say it out loud',
        marathi: 'धन्यवाद',
        roman: 'dhanyavād',
        english: 'Thank you',
      ),
      const Exercise(
        kind: Kind.build,
        prompt: 'Build the sentence',
        marathi: 'माझं नाव राहुल आहे',
        roman: 'mājha nāv Rahul āhe',
        english: 'My name is Rahul',
        bank: ['आहे', 'माझं', 'राहुल', 'नाव'],
      ),
    ]),
    Lesson('Introductions', Icons.person_rounded, [
      const Exercise(
        kind: Kind.choice,
        prompt: 'What does this mean?',
        marathi: 'तुमचं नाव काय आहे?',
        roman: 'tumcha nāv kāy āhe?',
        options: ['Where do you live?', "What is your name?", 'How are you?', 'Where are you from?'],
        answer: 1,
      ),
      const Exercise(
        kind: Kind.speak,
        prompt: 'Say it out loud',
        marathi: 'मला मराठी येत नाही',
        roman: 'malā marāṭhī yet nāhī',
        english: "I don't know Marathi",
      ),
      const Exercise(
        kind: Kind.speak,
        prompt: 'Say it out loud',
        marathi: 'कृपया हळू बोला',
        roman: 'kṛpayā haḷū bolā',
        english: 'Please speak slowly',
      ),
    ]),
  ]),
  Unit('कामाच्या ठिकाणी', 'At the workplace', Desi.teal, [
    Lesson('On site', Icons.construction_rounded, [
      const Exercise(
        kind: Kind.choice,
        prompt: 'What does this mean?',
        marathi: 'मला मदत हवी आहे',
        roman: 'malā madat havī āhe',
        options: ['I am hungry', 'I need help', 'I am tired', 'I am leaving'],
        answer: 1,
      ),
      const Exercise(
        kind: Kind.speak,
        prompt: 'Say it out loud',
        marathi: 'मला मदत हवी आहे',
        roman: 'malā madat havī āhe',
        english: 'I need help',
      ),
      const Exercise(
        kind: Kind.match,
        prompt: 'Tap the matching pairs',
        pairs: [
          ['पाणी', 'Water'],
          ['काम', 'Work'],
          ['सुट्टी', 'Holiday'],
          ['पगार', 'Wages'],
        ],
      ),
      const Exercise(
        kind: Kind.build,
        prompt: 'Build the sentence',
        marathi: 'पाणी कुठे मिळेल',
        roman: 'pāṇī kuṭhe miḷel',
        english: 'Where can I get water',
        bank: ['कुठे', 'पाणी', 'मिळेल'],
      ),
    ]),
    Lesson('Getting paid', Icons.payments_rounded, [
      const Exercise(
        kind: Kind.choice,
        prompt: 'What does this mean?',
        marathi: 'पगार कधी मिळेल?',
        roman: 'pagār kadhī miḷel?',
        options: ['How much is the pay?', 'When will I be paid?', 'Where is the office?', 'Who is the boss?'],
        answer: 1,
      ),
      const Exercise(
        kind: Kind.speak,
        prompt: 'Say it out loud',
        marathi: 'पगार कधी मिळेल',
        roman: 'pagār kadhī miḷel',
        english: 'When will I be paid',
      ),
      const Exercise(
        kind: Kind.speak,
        prompt: 'Say it out loud',
        marathi: 'उद्या सुट्टी आहे का',
        roman: 'udyā suṭṭī āhe kā',
        english: 'Is tomorrow a holiday',
      ),
    ]),
  ]),
  Unit('बाजारात', 'At the market', Desi.dusk, [
    Lesson('Buying things', Icons.storefront_rounded, [
      const Exercise(
        kind: Kind.choice,
        prompt: 'What does this mean?',
        marathi: 'हे किती रुपये?',
        roman: 'he kitī rupaye?',
        options: ['How many are there?', 'How much is this?', 'Is this fresh?', 'Do you have change?'],
        answer: 1,
      ),
      const Exercise(
        kind: Kind.speak,
        prompt: 'Say it out loud',
        marathi: 'हे किती रुपये',
        roman: 'he kitī rupaye',
        english: 'How much is this',
      ),
      const Exercise(
        kind: Kind.build,
        prompt: 'Build the sentence',
        marathi: 'मला हे नको आहे',
        roman: 'malā he nako āhe',
        english: "I don't want this",
        bank: ['नको', 'मला', 'आहे', 'हे'],
      ),
    ]),
  ]),
];
