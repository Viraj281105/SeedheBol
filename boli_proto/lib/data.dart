import 'package:flutter/material.dart';

/// Content model.
///
/// The atom is a **situation**, not a word and not a lesson. "Asking your
/// supervisor to correct an underpaid wage" is a situation. "Money vocabulary"
/// is not. Everything in the UI is organised around what the user will actually
/// have to do this week.

class Lang {
  final String code, native, english;
  final bool installed;
  final int mb;

  /// Offered as a language you already speak, but never as one you are
  /// learning. The product translates Indian language to Indian language with
  /// no English pivot; English is an explanation language, not a target.
  final bool l1Only;

  const Lang(
    this.code,
    this.native,
    this.english, {
    this.installed = false,
    this.mb = 187,
    this.l1Only = false,
  });
}

/// Languages offered as L2 — everything you can actually learn here.
List<Lang> get targetLanguages => languages.where((l) => !l.l1Only).toList();

/// The 22 scheduled languages are the destination. These are the ones the
/// prototype surfaces; only Marathi is actually resident on device.
const languages = <Lang>[
  Lang('mr', 'मराठी', 'Marathi', installed: true),
  Lang('hi', 'हिन्दी', 'Hindi'),
  Lang('ta', 'தமிழ்', 'Tamil'),
  Lang('te', 'తెలుగు', 'Telugu'),
  Lang('kn', 'ಕನ್ನಡ', 'Kannada'),
  Lang('ml', 'മലയാളം', 'Malayalam'),
  Lang('bn', 'বাংলা', 'Bengali'),
  Lang('gu', 'ગુજરાતી', 'Gujarati'),
  Lang('pa', 'ਪੰਜਾਬੀ', 'Punjabi'),
  Lang('or', 'ଓଡ଼ିଆ', 'Odia'),
  Lang('as', 'অসমীয়া', 'Assamese'),
  Lang('ur', 'اردو', 'Urdu'),
  // Last, deliberately: the point of the product is Indian languages, and a
  // list headed by English would say the opposite.
  Lang('en', 'English', 'English', installed: true, mb: 0, l1Only: true),
];

class Job {
  final String title, native;
  final IconData icon;
  const Job(this.title, this.native, this.icon);
}

const jobs = <Job>[
  Job('Construction', 'बांधकाम', Icons.foundation_rounded),
  Job('Nursing & care', 'रुग्णसेवा', Icons.medical_services_rounded),
  Job('Delivery & logistics', 'डिलिव्हरी', Icons.local_shipping_rounded),
  Job('Domestic work', 'घरकाम', Icons.cleaning_services_rounded),
  Job('Driving', 'वाहन चालक', Icons.local_taxi_rounded),
  Job('Shop & counter', 'दुकान', Icons.storefront_rounded),
  Job('Security', 'सुरक्षा', Icons.shield_rounded),
  Job('Factory floor', 'कारखाना', Icons.precision_manufacturing_rounded),
];

enum Kind { choice, speak, build, match }

class Exercise {
  final Kind kind;
  final String prompt, marathi, roman, english;
  final List<String> options;
  final int answer;
  final List<String> bank;
  final List<List<String>> pairs;

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

/// Where and when a situation happens. Drives the filter strip on Today, and
/// eventually the geofence trigger.
enum Ctx { work, money, market, health, home }

extension CtxInfo on Ctx {
  String get label => switch (this) {
    Ctx.work => 'On site',
    Ctx.money => 'Wages & rights',
    Ctx.market => 'Market',
    Ctx.health => 'Health',
    Ctx.home => 'Where you stay',
  };
  IconData get icon => switch (this) {
    Ctx.work => Icons.engineering_rounded,
    Ctx.money => Icons.payments_rounded,
    Ctx.market => Icons.shopping_basket_rounded,
    Ctx.health => Icons.local_hospital_rounded,
    Ctx.home => Icons.home_work_rounded,
  };
}

class Situation {
  final String title; // what you will be doing, in plain words
  final String native; // the same, in the target language
  final Ctx ctx;
  final int phrases;
  final double readiness; // 0..1 — coverage, never a score
  final bool urgent; // surfaced because it is likely needed this week
  final List<Exercise> exercises;

  const Situation({
    required this.title,
    required this.native,
    required this.ctx,
    required this.phrases,
    required this.readiness,
    this.urgent = false,
    this.exercises = const [],
  });

  Situation copyWith({
    String? title,
    String? native,
    Ctx? ctx,
    int? phrases,
    double? readiness,
    bool? urgent,
    List<Exercise>? exercises,
  }) => Situation(
    title: title ?? this.title,
    native: native ?? this.native,
    ctx: ctx ?? this.ctx,
    phrases: phrases ?? this.phrases,
    readiness: readiness ?? this.readiness,
    urgent: urgent ?? this.urgent,
    exercises: exercises ?? this.exercises,
  );
}

final situations = <Situation>[
  // ---- the demo situation ------------------------------------------------
  // Exercises every capability in one pass: listen, choose, match, speak, and
  // build. Every spoken phrase is three or four words -- single words prove
  // neither the synthesiser nor the recogniser.
  Situation(
    title: 'Starting your first day',
    native: 'पहिला दिवस',
    ctx: Ctx.work,
    phrases: 8,
    readiness: .15,
    urgent: true,
    exercises: const [
      // 1. Hear it, then choose what it means.
      Exercise(
        kind: Kind.choice,
        prompt: 'What does this mean?',
        marathi: 'मला मदत हवी आहे',
        roman: 'malā madat havī āhe',
        options: [
          'I am going home',
          'I need help',
          'I am very tired',
          'I do not want this',
        ],
        answer: 1,
      ),
      // 2. Say it back. Four words.
      Exercise(
        kind: Kind.speak,
        prompt: 'Say it out loud',
        marathi: 'मला मदत हवी आहे',
        roman: 'malā madat havī āhe',
        english: 'I need help',
      ),
      // 3. The words you will need on site.
      Exercise(
        kind: Kind.match,
        prompt: 'Match the pairs',
        pairs: [
          ['पाणी', 'Water'],
          ['काम', 'Work'],
          ['पगार', 'Wages'],
          ['सुट्टी', 'Holiday'],
        ],
      ),
      // 4. Three words.
      Exercise(
        kind: Kind.speak,
        prompt: 'Say it out loud',
        marathi: 'पाणी कुठे मिळेल',
        roman: 'pāṇī kuṭhe miḷel',
        english: 'Where can I get water',
      ),
      // 5. Listen, then choose.
      Exercise(
        kind: Kind.choice,
        prompt: 'What does this mean?',
        marathi: 'पगार कधी मिळेल',
        roman: 'pagār kadhī miḷel',
        options: [
          'How much is the pay?',
          'Where is the office?',
          'When will I be paid?',
          'Who is the supervisor?',
        ],
        answer: 2,
      ),
      // 6. Three words.
      Exercise(
        kind: Kind.speak,
        prompt: 'Say it out loud',
        marathi: 'कृपया हळू बोला',
        roman: 'kṛpayā haḷū bolā',
        english: 'Please speak slowly',
      ),
      // 7. Four words. Sentence building closes the lesson.
      Exercise(
        kind: Kind.build,
        prompt: 'Build the sentence',
        marathi: 'माझं नाव राहुल आहे',
        roman: 'mājha nāv Rahul āhe',
        english: 'My name is Rahul',
        bank: ['आहे', 'माझं', 'राहुल', 'नाव'],
      ),
    ],
  ),
  Situation(
    title: 'Telling someone you do not follow',
    native: 'मला समजलं नाही',
    ctx: Ctx.work,
    phrases: 6,
    readiness: .7,
    exercises: const [
      Exercise(
        kind: Kind.speak,
        prompt: 'Say it out loud',
        marathi: 'मला मराठी येत नाही',
        roman: 'malā marāṭhī yet nāhī',
        english: "I don't know Marathi",
      ),
      Exercise(
        kind: Kind.speak,
        prompt: 'Say it out loud',
        marathi: 'कृपया हळू बोला',
        roman: 'kṛpayā haḷū bolā',
        english: 'Please speak slowly',
      ),
      Exercise(
        kind: Kind.choice,
        prompt: 'What does this mean?',
        marathi: 'तुमचं नाव काय आहे?',
        roman: 'tumcha nāv kāy āhe?',
        options: [
          'Where do you live?',
          'What is your name?',
          'How are you?',
          'Where are you from?',
        ],
        answer: 1,
      ),
    ],
  ),
  Situation(
    title: 'Asking for help on site',
    native: 'मदत मागणे',
    ctx: Ctx.work,
    phrases: 7,
    readiness: .35,
    urgent: true,
    exercises: const [
      Exercise(
        kind: Kind.choice,
        prompt: 'What does this mean?',
        marathi: 'मला मदत हवी आहे',
        roman: 'malā madat havī āhe',
        options: ['I am hungry', 'I need help', 'I am tired', 'I am leaving'],
        answer: 1,
      ),
      Exercise(
        kind: Kind.speak,
        prompt: 'Say it out loud',
        marathi: 'मला मदत हवी आहे',
        roman: 'malā madat havī āhe',
        english: 'I need help',
      ),
      Exercise(
        kind: Kind.match,
        prompt: 'Match the pairs',
        pairs: [
          ['पाणी', 'Water'],
          ['काम', 'Work'],
          ['सुट्टी', 'Holiday'],
          ['पगार', 'Wages'],
        ],
      ),
      Exercise(
        kind: Kind.build,
        prompt: 'Build the sentence',
        marathi: 'पाणी कुठे मिळेल',
        roman: 'pāṇī kuṭhe miḷel',
        english: 'Where can I get water',
        bank: ['कुठे', 'पाणी', 'मिळेल'],
      ),
    ],
  ),
  Situation(
    title: 'Asking when you will be paid',
    native: 'पगाराबद्दल विचारणे',
    ctx: Ctx.money,
    phrases: 9,
    readiness: .15,
    urgent: true,
    exercises: const [
      Exercise(
        kind: Kind.choice,
        prompt: 'What does this mean?',
        marathi: 'पगार कधी मिळेल?',
        roman: 'pagār kadhī miḷel?',
        options: [
          'How much is the pay?',
          'When will I be paid?',
          'Where is the office?',
          'Who is the boss?',
        ],
        answer: 1,
      ),
      Exercise(
        kind: Kind.speak,
        prompt: 'Say it out loud',
        marathi: 'पगार कधी मिळेल',
        roman: 'pagār kadhī miḷel',
        english: 'When will I be paid',
      ),
      Exercise(
        kind: Kind.speak,
        prompt: 'Say it out loud',
        marathi: 'उद्या सुट्टी आहे का',
        roman: 'udyā suṭṭī āhe kā',
        english: 'Is tomorrow a holiday',
      ),
    ],
  ),
  const Situation(
    title: 'Saying a payment is short',
    native: 'पैसे कमी आहेत',
    ctx: Ctx.money,
    phrases: 6,
    readiness: 0,
  ),
  Situation(
    title: 'Buying food and daily things',
    native: 'खरेदी करणे',
    ctx: Ctx.market,
    phrases: 11,
    readiness: .5,
    exercises: const [
      Exercise(
        kind: Kind.choice,
        prompt: 'What does this mean?',
        marathi: 'हे किती रुपये?',
        roman: 'he kitī rupaye?',
        options: [
          'How many are there?',
          'How much is this?',
          'Is this fresh?',
          'Do you have change?',
        ],
        answer: 1,
      ),
      Exercise(
        kind: Kind.speak,
        prompt: 'Say it out loud',
        marathi: 'हे किती रुपये',
        roman: 'he kitī rupaye',
        english: 'How much is this',
      ),
      Exercise(
        kind: Kind.build,
        prompt: 'Build the sentence',
        marathi: 'मला हे नको आहे',
        roman: 'malā he nako āhe',
        english: "I don't want this",
        bank: ['नको', 'मला', 'आहे', 'हे'],
      ),
    ],
  ),
  const Situation(
    title: 'Reporting an injury',
    native: 'दुखापत सांगणे',
    ctx: Ctx.health,
    phrases: 10,
    readiness: 0,
    urgent: true,
  ),
  const Situation(
    title: 'At the chemist',
    native: 'औषधाच्या दुकानात',
    ctx: Ctx.health,
    phrases: 7,
    readiness: 0,
  ),
  const Situation(
    title: 'Talking to your landlord',
    native: 'घरमालकाशी बोलणे',
    ctx: Ctx.home,
    phrases: 8,
    readiness: 0,
  ),
  const Situation(
    title: 'Asking directions to the site',
    native: 'रस्ता विचारणे',
    ctx: Ctx.home,
    phrases: 5,
    readiness: .25,
  ),
];

/// Phone-native capabilities, surfaced as tools rather than buried in a menu.
/// Only `speak` is wired to real inference in this prototype; the rest are
/// interface shells that show the intended interaction.
class Tool {
  final String title, subtitle, native;
  final IconData icon;
  final Color tint;
  final bool live;
  const Tool(
    this.title,
    this.native,
    this.subtitle,
    this.icon,
    this.tint, {
    this.live = false,
  });
}
