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
  Lang('ta', 'தமிழ்', 'Tamil', installed: true),
  Lang('hi', 'हिन्दी', 'Hindi', installed: true),
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
  Job('Restaurant & Hotel', 'हॉटेल आणि भोजनालय', Icons.restaurant_rounded),
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
  final String prompt;
  final String promptL1;
  final String marathi; // Target L2 utterance
  final String devanagariPhonetic; // Devanagari transliteration for non-Tamil readers
  final String roman, english;
  final List<String> options;
  final int answer;
  final List<String> bank;
  final List<List<String>> pairs;

  String get targetText => marathi;

  const Exercise({
    required this.kind,
    required this.prompt,
    this.promptL1 = '',
    this.marathi = '',
    this.devanagariPhonetic = '',
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

// -----------------------------------------------------------------------------
// MARATHI RESTAURANT & HOSPITALITY CORRIDOR
// -----------------------------------------------------------------------------
final marathiRestaurantSituations = <Situation>[
  Situation(
    title: 'Taking customer food orders',
    native: 'ऑर्डर घेणे',
    ctx: Ctx.work,
    phrases: 8,
    readiness: .20,
    urgent: true,
    exercises: const [
      Exercise(
        kind: Kind.choice,
        prompt: 'What does this mean?',
        promptL1: 'याचा अर्थ काय? (Hindi: इसका क्या मतलब है?)',
        marathi: 'दोन स्पेशल चहा आणि एक वडापाव',
        roman: 'don special chaha aani ek vadapav',
        devanagariPhonetic: 'दोन स्पेशल चहा आणि एक वडापाव',
        english: 'Two special teas and one vadapav',
        options: [
          'Bring five cups of water',
          'Two special teas and one vadapav',
          'Clean table number four',
          'Give bill for the food',
        ],
        answer: 1,
      ),
      Exercise(
        kind: Kind.speak,
        prompt: 'Say it out loud',
        promptL1: 'मोठ्याने बोला (माइक दाबा आणि बोला)',
        marathi: 'दोन स्पेशल चहा आणि एक वडापाव',
        roman: 'don special chaha aani ek vadapav',
        devanagariPhonetic: 'दोन स्पेशल चहा आणि एक वडापाव',
        english: 'Two special teas and one vadapav',
      ),
      Exercise(
        kind: Kind.match,
        prompt: 'Match the food pairs',
        promptL1: 'जोड्या लावा (Pairs)',
        pairs: [
          ['चहा', 'Tea'],
          ['नाष्टा', 'Snacks'],
          ['पाणी', 'Water'],
          ['बिल', 'Bill'],
        ],
      ),
      Exercise(
        kind: Kind.speak,
        prompt: 'Say it out loud',
        promptL1: 'मोठ्याने बोला',
        marathi: 'पार्सल वेगळ्या पिशवीत द्या',
        roman: 'parcel veglya pishveet dya',
        devanagariPhonetic: 'पार्सल वेगळ्या पिशवीत द्या',
        english: 'Give the parcel in a separate bag',
      ),
      Exercise(
        kind: Kind.build,
        prompt: 'Build the sentence',
        promptL1: 'वाक्य बनवा',
        marathi: 'दोन स्पेशल चहा द्या',
        roman: 'don special chaha dya',
        devanagariPhonetic: 'दोन स्पेशल चहा द्या',
        english: 'Give two special teas',
        bank: ['द्या', 'चहा', 'दोन', 'स्पेशल'],
      ),
    ],
  ),
  Situation(
    title: 'Kitchen coordination & safety',
    native: 'स्वयंपाकघर आणि स्वच्छता',
    ctx: Ctx.work,
    phrases: 6,
    readiness: .35,
    urgent: true,
    exercises: const [
      Exercise(
        kind: Kind.choice,
        prompt: 'What does this mean?',
        promptL1: 'याचा अर्थ काय?',
        marathi: 'तेल खूप गरम आहे सांभाळा',
        roman: 'tel khoop garam aahe sambhala',
        devanagariPhonetic: 'तेल खूप गरम आहे सांभाळा',
        english: 'Oil is very hot, be careful',
        options: [
          'Wash the knives now',
          'Oil is very hot, be careful',
          'Turn off the refrigerator',
          'Dinner is ready',
        ],
        answer: 1,
      ),
      Exercise(
        kind: Kind.speak,
        prompt: 'Say it out loud',
        promptL1: 'मोठ्याने बोला',
        marathi: 'भाजीत मीठ कमी आहे',
        roman: 'bhajit meeth kami aahe',
        devanagariPhonetic: 'भाजीत मीठ कमी आहे',
        english: 'Salt is low in the vegetable curry',
      ),
      Exercise(
        kind: Kind.speak,
        prompt: 'Say it out loud',
        promptL1: 'मोठ्याने बोला',
        marathi: 'टेबल पटकन स्वच्छ करा',
        roman: 'table patkan swachh kara',
        devanagariPhonetic: 'टेबल पटकन स्वच्छ करा',
        english: 'Clean the table quickly',
      ),
    ],
  ),
  Situation(
    title: 'Billing, change & payment',
    native: 'बिल आणि सुट्टे पैसे',
    ctx: Ctx.money,
    phrases: 7,
    readiness: .10,
    urgent: true,
    exercises: const [
      Exercise(
        kind: Kind.speak,
        prompt: 'Say it out loud',
        promptL1: 'मोठ्याने बोला',
        marathi: 'पाचशे रुपयांचे सुट्टे आहेत का',
        roman: 'pachshe rupayanche sutte aahet ka',
        devanagariPhonetic: 'पाचशे रुपयांचे सुट्टे आहेत का',
        english: 'Do you have change for 500 rupees',
      ),
      Exercise(
        kind: Kind.choice,
        prompt: 'What does this mean?',
        promptL1: 'याचा अर्थ काय?',
        marathi: 'एकूण किती बिल झाले?',
        roman: 'ekun kiti bill jhale?',
        devanagariPhonetic: 'एकूण किती बिल झाले?',
        english: 'How much is the total bill?',
        options: [
          'What time do we close?',
          'How much is the total bill?',
          'Is table two free?',
          'Who is the manager?',
        ],
        answer: 1,
      ),
      Exercise(
        kind: Kind.build,
        prompt: 'Build the sentence',
        promptL1: 'वाक्य बनवा',
        marathi: 'पाचशे रुपयांचे सुट्टे द्या',
        roman: 'pachshe rupayanche sutte dya',
        devanagariPhonetic: 'पाचशे रुपयांचे सुट्टे द्या',
        english: 'Give change for 500 rupees',
        bank: ['द्या', 'सुट्टे', 'रुपयांचे', 'पाचशे'],
      ),
    ],
  ),
];

// -----------------------------------------------------------------------------
// TAMIL CONSTRUCTION CORRIDOR (BHOJPURI / HINDI -> TAMIL)
// -----------------------------------------------------------------------------
final tamilConstructionSituations = <Situation>[
  Situation(
    title: 'Starting your first day on site',
    native: 'முதல் நாள் வேலை',
    ctx: Ctx.work,
    phrases: 8,
    readiness: .15,
    urgent: true,
    exercises: const [
      Exercise(
        kind: Kind.choice,
        prompt: 'What does this mean?',
        promptL1: 'இதன் அர்த்தம் என்ன? (Hindi: इसका अर्थ क्या है?)',
        marathi: 'வணக்கம் மேஸ்திரி',
        roman: 'vanakkam mesthiri',
        devanagariPhonetic: 'वणक्कम मेस्तरी',
        english: 'Greetings supervisor',
        options: [
          'I am going home',
          'Greetings supervisor',
          'Where is the water tank?',
          'Give me the hammer',
        ],
        answer: 1,
      ),
      Exercise(
        kind: Kind.speak,
        prompt: 'Say it out loud',
        promptL1: 'உரக்க சொல்லுங்கள் (माइक दाबा आणि बोला)',
        marathi: 'வணக்கம் மேஸ்திரி',
        roman: 'vanakkam mesthiri',
        devanagariPhonetic: 'वणक्कम मेस्तरी',
        english: 'Greetings supervisor',
      ),
      Exercise(
        kind: Kind.match,
        prompt: 'Match the site pairs',
        promptL1: 'பொருத்துங்கள் (Pairs)',
        pairs: [
          ['தண்ணீர்', 'Water'],
          ['வேலை', 'Work'],
          ['கூலி', 'Wages'],
          ['மேஸ்திரி', 'Supervisor'],
        ],
      ),
      Exercise(
        kind: Kind.speak,
        prompt: 'Say it out loud',
        promptL1: 'உரக்க சொல்லுங்கள்',
        marathi: 'தண்ணீர் எங்கே கிடைக்கும்',
        roman: 'thanneer enge kidaikkum',
        devanagariPhonetic: 'तण्णीर एङ्गे किडैक्कुम',
        english: 'Where can I get water',
      ),
      Exercise(
        kind: Kind.build,
        prompt: 'Build the sentence',
        promptL1: 'வாக்கியம் அமையுங்கள்',
        marathi: 'என் பெயர் ராகுல்',
        roman: 'en peyar Rahul',
        devanagariPhonetic: 'एन पेयर राहुल',
        english: 'My name is Rahul',
        bank: ['ராகுல்', 'என்', 'பெயர்'],
      ),
    ],
  ),
  Situation(
    title: 'Wage dispute & daily payment',
    native: 'கூலி குறைப்பு பற்றி பேசுதல்',
    ctx: Ctx.money,
    phrases: 9,
    readiness: .10,
    urgent: true,
    exercises: const [
      Exercise(
        kind: Kind.choice,
        prompt: 'What does this mean?',
        promptL1: 'இதன் அர்த்தம் என்ன?',
        marathi: 'வார சம்பளத்துல ஒரு நாள் கூலி குறையுது',
        roman: 'vaara sambalathula oru naal kooli kuraiyudhu',
        devanagariPhonetic: 'वार सम्बलत्तिल ओरु नाळ कूली कुरैयुदु',
        english: 'One day wage is short in weekly pay',
        options: [
          'I want next month advance',
          'One day wage is short in weekly pay',
          'Tomorrow is a site holiday',
          'Where is the accountant?',
        ],
        answer: 1,
      ),
      Exercise(
        kind: Kind.speak,
        prompt: 'Say it out loud',
        promptL1: 'உரக்க சொல்லுங்கள்',
        marathi: 'போன வாரம் ஆறு நாள் வேலை செஞ்சேன்',
        roman: 'pona vaaram aaru naal velai senjen',
        devanagariPhonetic: 'पोन वारम आरु नाळ वेलै सेञ्जेन',
        english: 'Last week I worked six days',
      ),
      Exercise(
        kind: Kind.speak,
        prompt: 'Say it out loud',
        promptL1: 'உரக்க சொல்லுங்கள்',
        marathi: 'கூலி எப்போ கிடைக்கும்',
        roman: 'kooli eppo kidaikkum',
        devanagariPhonetic: 'कूली एप्पो किडैक्कुम',
        english: 'When will wages be paid',
      ),
    ],
  ),
  Situation(
    title: 'Site safety & warning commands',
    native: 'தள பாதுகாப்பு எச்சரிக்கைகள்',
    ctx: Ctx.work,
    phrases: 7,
    readiness: .30,
    urgent: true,
    exercises: const [
      Exercise(
        kind: Kind.choice,
        prompt: 'What does this mean?',
        promptL1: 'இதன் அர்த்தம் என்ன?',
        marathi: 'ஹெல்மெட் போடாம உள்ள போகாதீங்க',
        roman: 'helmet podama ulla pogaadheenga',
        devanagariPhonetic: 'हेल्मेट पोडाम उल्ल पोगादीङ्ग',
        english: 'Do not go inside without wearing a helmet',
        options: [
          'Bring safety gloves',
          'Do not go inside without wearing a helmet',
          'Pillar casting is finished',
          'Mix the sand and cement',
        ],
        answer: 1,
      ),
      Exercise(
        kind: Kind.speak,
        prompt: 'Say it out loud',
        promptL1: 'உரக்க சொல்லுங்கள்',
        marathi: 'ஜாக்கிரதை, மேலே வேலை நடக்குது',
        roman: 'jaakkiradhai, mele velai nadakkudhu',
        devanagariPhonetic: 'जाक्किरदै, मेले वेलै नडक्कुदु',
        english: 'Be careful, work is going on above',
      ),
    ],
  ),
];

// -----------------------------------------------------------------------------
// TAMIL RESTAURANT & HOSPITALITY CORRIDOR
// -----------------------------------------------------------------------------
final tamilRestaurantSituations = <Situation>[
  Situation(
    title: 'Taking customer food orders',
    native: 'வாடிக்கையாளர் ஆர்டர் எடுத்தல்',
    ctx: Ctx.work,
    phrases: 8,
    readiness: .20,
    urgent: true,
    exercises: const [
      Exercise(
        kind: Kind.choice,
        prompt: 'What does this mean?',
        promptL1: 'இதன் அர்த்தம் என்ன? (Hindi: इसका क्या मतलब है?)',
        marathi: 'ரெண்டு தோசை, ஒரு ஃபில்டர் காபி',
        roman: 'rendu dosai, oru filter coffee',
        devanagariPhonetic: 'रेण्डु दोसै, ओरु फिल्टर कापी',
        english: 'Two dosas and one filter coffee',
        options: [
          'Pack two meals',
          'Two dosas and one filter coffee',
          'Bring cold water bottle',
          'Bill is five hundred rupees',
        ],
        answer: 1,
      ),
      Exercise(
        kind: Kind.speak,
        prompt: 'Say it out loud',
        promptL1: 'உரக்க சொல்லுங்கள்',
        marathi: 'ரெண்டு தோசை, ஒரு ஃபில்டர் காபி',
        roman: 'rendu dosai, oru filter coffee',
        devanagariPhonetic: 'रेण्डु दोसै, ओरु फिल्टर कापी',
        english: 'Two dosas and one filter coffee',
      ),
      Exercise(
        kind: Kind.match,
        prompt: 'Match the food items',
        promptL1: 'பொருத்துங்கள்',
        pairs: [
          ['தோசை', 'Dosa'],
          ['காபி', 'Coffee'],
          ['சாம்பார்', 'Sambar'],
          ['பார்சல்', 'Takeaway Parcel'],
        ],
      ),
      Exercise(
        kind: Kind.speak,
        prompt: 'Say it out loud',
        promptL1: 'உரக்க சொல்லுங்கள்',
        marathi: 'பார்சல் தனி பையில் போடுங்க',
        roman: 'parcel thani paiyil podunga',
        devanagariPhonetic: 'पार्सल तनि पैयिल् पोडुङ्ग',
        english: 'Put the parcel in a separate bag',
      ),
      Exercise(
        kind: Kind.build,
        prompt: 'Build the sentence',
        promptL1: 'வாக்கியம் அமையுங்கள்',
        marathi: 'ரெண்டு கப் டீ கொடுங்க',
        roman: 'rendu cup tea kodunga',
        devanagariPhonetic: 'रेण्डु कप टी कोडुङ्ग',
        english: 'Give two cups of tea',
        bank: ['கொடுங்க', 'டீ', 'ரெண்டு', 'கப்'],
      ),
    ],
  ),
  Situation(
    title: 'Kitchen coordination & safety',
    native: 'அடுப்பங்கரை வேலை & பாதுகாப்பு',
    ctx: Ctx.work,
    phrases: 6,
    readiness: .30,
    urgent: true,
    exercises: const [
      Exercise(
        kind: Kind.choice,
        prompt: 'What does this mean?',
        promptL1: 'இதன் அர்த்தம் என்ன?',
        marathi: 'எண்ணெய் ரொம்ப சூடா இருக்கு',
        roman: 'ennai romba sooda irukku',
        devanagariPhonetic: 'एण्णै रोम्ब सूडा इरिक्कु',
        english: 'Oil is very hot, be careful',
        options: [
          'Wash table plates',
          'Oil is very hot, be careful',
          'Sambar is finished',
          'Close the dining room',
        ],
        answer: 1,
      ),
      Exercise(
        kind: Kind.speak,
        prompt: 'Say it out loud',
        promptL1: 'உரக்க சொல்லுங்கள்',
        marathi: 'சாம்பார்ல கொஞ்சம் உப்பு போடுங்க',
        roman: 'sambaarla konjam uppu podunga',
        devanagariPhonetic: 'साम्बार्ल कोञ्जम उप्पु पोडुङ्ग',
        english: 'Put a little salt in the sambar',
      ),
      Exercise(
        kind: Kind.speak,
        prompt: 'Say it out loud',
        promptL1: 'உரக்க சொல்லுங்கள்',
        marathi: 'மேஜையை உடனே துடைங்க',
        roman: 'mejaiyai udane thudainga',
        devanagariPhonetic: 'मेजैयै उडने तुडैङ्ग',
        english: 'Wipe the dining table immediately',
      ),
    ],
  ),
  Situation(
    title: 'Billing, change & payment',
    native: 'பில் மற்றும் சில்லறை',
    ctx: Ctx.money,
    phrases: 7,
    readiness: .15,
    urgent: true,
    exercises: const [
      Exercise(
        kind: Kind.speak,
        prompt: 'Say it out loud',
        promptL1: 'உரக்க சொல்லுங்கள்',
        marathi: 'ஐநூறு ரூபாய்க்கு சில்லறை கொடுங்க',
        roman: 'ainooru roobaikku sillarai kodunga',
        devanagariPhonetic: 'ऐनूरु रूपायिक्कु चिल्लरै कोडुङ्ग',
        english: 'Give change for 500 rupees',
      ),
      Exercise(
        kind: Kind.choice,
        prompt: 'What does this mean?',
        promptL1: 'இதன் அர்த்தம் என்ன?',
        marathi: 'மொத்தம் எவ்வளவு பில் ஆச்சு?',
        roman: 'motham evvalavu bill aachu?',
        devanagariPhonetic: 'मोत्तम एव्वळवु बिल आच्चु?',
        english: 'How much is the total bill?',
        options: [
          'Where is the chef?',
          'How much is the total bill?',
          'Table three is empty',
          'Tea is ready',
        ],
        answer: 1,
      ),
      Exercise(
        kind: Kind.build,
        prompt: 'Build the sentence',
        promptL1: 'வாக்கியம் அமையுங்கள்',
        marathi: 'சில்லறை கையில் கொடுங்க',
        roman: 'sillarai kaiyil kodunga',
        devanagariPhonetic: 'चिल्लरै कैयिल् कोडुङ्ग',
        english: 'Hand over the change',
        bank: ['கொடுங்க', 'சில்லறை', 'கையில்'],
      ),
    ],
  ),
];

/// Dynamic resolver that yields the correct curriculum based on target language and trade
List<Situation> getSituationsFor({Lang? l2, Job? job}) {
  final langCode = l2?.code ?? 'mr';
  final jobTitle = job?.title.toLowerCase() ?? 'construction';
  final isRestaurant = jobTitle.contains('restaurant') || jobTitle.contains('hotel');

  if (langCode == 'ta') {
    return isRestaurant ? tamilRestaurantSituations : tamilConstructionSituations;
  } else if (langCode == 'mr') {
    return isRestaurant ? marathiRestaurantSituations : situations;
  } else {
    // Fallback gracefully to standard situations
    return isRestaurant ? marathiRestaurantSituations : situations;
  }
}


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
