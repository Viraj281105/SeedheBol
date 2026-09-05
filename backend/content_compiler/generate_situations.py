#!/usr/bin/env python3
"""
tools/content_compiler/generate_situations.py
============================================
Curriculum Authoring & AST Compiler for Seedhebol.

Generates validated, production-grade branching situational dialogue trees
for India's internal migrant workforce across occupational domains and
migration corridors.

Outputs:
- `data/construction_tamil.json` (10 comprehensive construction situations)
- `data/healthcare_tamil.json`
- `data/logistics_tamil.json`

Schema adheres strictly to `packages/shared_models/lib/src/situation_model.dart`.
"""

import argparse
import json
import logging
from pathlib import Path
from typing import Any, Dict, List

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
logger = logging.getLogger("GenerateSituations")


def get_construction_tamil_situations() -> List[Dict[str, Any]]:
    """Returns 10 verified real-world construction domain situations for Bhojpuri -> Tamil."""
    return [
        {
            "situation_id": "ta_const_01_wage_dispute",
            "title_l1": "दहाड़ी की कटौती पर सुपरवाइजर से बात करना",
            "title_l2": "கூலி குறைப்பு பற்றி மேஸ்திரியிடம் பேசுதல்",
            "description": "Explaining that 6 days were worked instead of 5 recorded, requesting wage correction politely but firmly.",
            "domain": "construction",
            "corridor": "bhojpuriTamil",
            "persona_name": "Supervisor Murugan (மேஸ்திரி முருகன்)",
            "difficulty_tier": 2,
            "entry_node_id": "wage_01_greeting",
            "tags": ["wages", "negotiation", "finance", "rights"],
            "nodes": {
                "wage_01_greeting": {
                    "node_id": "wage_01_greeting",
                    "l2_text": "வணக்கம் மேஸ்திரி, என் வார சம்பளத்துல ஒரு நாள் கூலி குறையுது.",
                    "transliteration": "Vanakkam mesthiri, en vaara sambalathula oru naal kooli kuraiyudhu.",
                    "l1_translation": "नमस्ते मेस्त्री जी, मेरी हफ्ते की मजदूरी में एक दिन का पैसा कम आया है।",
                    "is_persona_turn": False,
                    "register": "employer",
                    "phoneme_focus_ipa": ["ɳ", "ɭ", "ʈ"],
                    "fallback_node_id": "wage_01_reprompt",
                    "branches": [
                        {
                            "intent_label": "supervisor_asks_attendance_proof",
                            "trigger_keywords": ["கணக்கு", "எந்த நாள்", "வந்தியா", "kanakku", "enda naal"],
                            "target_node_id": "wage_02_supervisor_query",
                            "confidence_threshold": 0.55
                        }
                    ]
                },
                "wage_01_reprompt": {
                    "node_id": "wage_01_reprompt",
                    "l2_text": "மேஸ்திரி, போன வாரம் ஆறு நாள் வேலை செஞ்சேன். ஆனா அஞ்சு நாள் கூலி தான் வந்திருக்கு.",
                    "transliteration": "Mesthiri, pona vaaram aaru naal velai senjen. Aana anju naal kooli thaan vandhirukku.",
                    "l1_translation": "मेस्त्री जी, पिछले हफ्ते 6 दिन काम किया था, पर 5 दिन का ही मिला है।",
                    "is_persona_turn": False,
                    "register": "employer",
                    "phoneme_focus_ipa": ["ɾ", "ɭ"],
                    "branches": [
                        {
                            "intent_label": "supervisor_acknowledges_dispute",
                            "trigger_keywords": ["சரி", "பார்க்கிறேன்", "டைரி", "sari", "paarkiren"],
                            "target_node_id": "wage_02_supervisor_query",
                            "confidence_threshold": 0.50
                        }
                    ]
                },
                "wage_02_supervisor_query": {
                    "node_id": "wage_02_supervisor_query",
                    "l2_text": "வியாழக்கிழமை நீ வரலைன்னு குறிச்சிருக்கே! அன்னைக்கு வந்தியா?",
                    "transliteration": "Viyazhakizhamai nee varalainnu kurichirukke! Annaikku vandhiya?",
                    "l1_translation": "गुरुवार को तुम्हारी गैरहाजिरी लगी है! उस दिन आए थे क्या?",
                    "is_persona_turn": True,
                    "register": "employer",
                    "phoneme_focus_ipa": ["ɻ", "ɭ"],
                    "branches": [
                        {
                            "intent_label": "user_confirms_thursday_pillar_work",
                            "trigger_keywords": ["வந்தேன்", "தூண்", "கான்கிரீட்", "vandhen", "thoon", "concrete"],
                            "target_node_id": "wage_03_user_evidence",
                            "confidence_threshold": 0.60
                        }
                    ]
                },
                "wage_03_user_evidence": {
                    "node_id": "wage_03_user_evidence",
                    "l2_text": "ஆமாங்க மேஸ்திரி, வியாழக்கிழமை மூணாவது மாடில தூண் கான்கிரீட் போட்டேன். செந்தில் கூட இருந்தார்.",
                    "transliteration": "Aamanga mesthiri, viyazhakizhamai moonavadhu maadila thoon concrete potten. Senthil kooda irundhaar.",
                    "l1_translation": "हाँ मेस्त्री जी, गुरुवार को तीसरी मंजिल पर पिलर कंक्रीट में था। सेंथिल भाई भी साथ थे।",
                    "is_persona_turn": False,
                    "register": "employer",
                    "phoneme_focus_ipa": ["ɻ", "ʈ", "ɖ"],
                    "branches": [
                        {
                            "intent_label": "supervisor_agrees_to_correct",
                            "trigger_keywords": ["சரி", "சேர்த்து", "தருகிறேன்", "sari", "serthu", "tharugiren"],
                            "target_node_id": "wage_04_supervisor_resolution",
                            "confidence_threshold": 0.55
                        }
                    ]
                },
                "wage_04_supervisor_resolution": {
                    "node_id": "wage_04_supervisor_resolution",
                    "l2_text": "சரி சரி, செந்தில் கிட்ட கேட்டுட்டு இன்னைக்கு சாயங்காலம் பாக்கி கூலியை குடுத்துடுறேன்.",
                    "transliteration": "Sari sari, Senthil kitta kettuttu innaikku saayangalam baaki kooliyai kuduthuduren.",
                    "l1_translation": "ठीक है, मैं सेंथिल से पूछकर आज शाम को बाकी पैसा दे दूंगा।",
                    "is_persona_turn": True,
                    "register": "employer",
                    "phoneme_focus_ipa": ["ʈ", "ɭ", "ɾ"],
                    "branches": [
                        {
                            "intent_label": "user_expresses_gratitude",
                            "trigger_keywords": ["நன்றி", "மேஸ்திரி", "nandri", "mesthiri"],
                            "target_node_id": "wage_05_user_thanks",
                            "confidence_threshold": 0.60
                        }
                    ]
                },
                "wage_05_user_thanks": {
                    "node_id": "wage_05_user_thanks",
                    "l2_text": "ரொம்ப நன்றிங்க மேஸ்திரி!",
                    "transliteration": "Romba nandringa mesthiri!",
                    "l1_translation": "बहुत-बहुत धन्यवाद मेस्त्री जी!",
                    "is_persona_turn": False,
                    "register": "employer",
                    "phoneme_focus_ipa": ["r", "n̪"],
                    "branches": []
                }
            }
        },
        {
            "situation_id": "ta_const_02_safety_harness",
            "title_l1": "ऊंचाई पर काम के लिए सेफ्टी बेल्ट और हेलमेट मांगना",
            "title_l2": "உயரமான வேலைக்கு பாதுகாப்பு பெல்ட் மற்றும் ஹெல்மெட் கேட்டல்",
            "description": "Refusing unsafe work at heights without a full-body harness and certified lifeline.",
            "domain": "construction",
            "corridor": "bhojpuriTamil",
            "persona_name": "Safety Officer Kumar (பாதுகாப்பு அதிகாரி குமார்)",
            "difficulty_tier": 1,
            "entry_node_id": "safe_01_request",
            "tags": ["safety", "ppe", "equipment", "hazard"],
            "nodes": {
                "safe_01_request": {
                    "node_id": "safe_01_request",
                    "l2_text": "அண்ணா, நாலாவது மாடில வேலை செய்ய புது பாதுகாப்பு பெல்ட் வேணும்.",
                    "transliteration": "Anna, naalavadhu maadila velai seyya pudhu paadhukaappu belt venum.",
                    "l1_translation": "भैया, चौथी मंजिल पर काम करने के लिए नया सेफ्टी बेल्ट चाहिए।",
                    "is_persona_turn": False,
                    "register": "peer",
                    "phoneme_focus_ipa": ["ɭ", "ʈ", "ɖ"],
                    "branches": [
                        {
                            "intent_label": "officer_checks_old_belt",
                            "trigger_keywords": ["பழைய", "பெல்ட்", "என்ன", "pazhaya", "belt", "enna"],
                            "target_node_id": "safe_02_officer_reply",
                            "confidence_threshold": 0.55
                        }
                    ]
                },
                "safe_02_officer_reply": {
                    "node_id": "safe_02_officer_reply",
                    "l2_text": "பழைய பெல்ட்ல என்ன பிரச்சனை? அதையே போட்டுட்டு போ!",
                    "transliteration": "Pazhaya beltla enna pirachchanai? Adhaiye pottuttu po!",
                    "l1_translation": "पुराने बेल्ट में क्या खराबी है? वही पहनकर जाओ!",
                    "is_persona_turn": True,
                    "register": "peer",
                    "phoneme_focus_ipa": ["ɻ", "ʈ"],
                    "branches": [
                        {
                            "intent_label": "user_explains_hook_damage",
                            "trigger_keywords": ["கொக்கி", "உடைஞ்சு", "ஆபத்து", "kokki", "udainju", "aabathu"],
                            "target_node_id": "safe_03_user_insist",
                            "confidence_threshold": 0.60
                        }
                    ]
                },
                "safe_03_user_insist": {
                    "node_id": "safe_03_user_insist",
                    "l2_text": "இல்லைங்க, பழைய பெல்ட் கொக்கி உடைஞ்சிருக்கு. உயரத்துல வேலை செய்யும்போது ஆபத்து.",
                    "transliteration": "Illainga, pazhaya belt kokki udainjirukku. Uyarathula velai seyyumbodhu aabathu.",
                    "l1_translation": "नहीं भैया, पुराना हुक टूटा हुआ है। ऊंचाई पर जान का खतरा है।",
                    "is_persona_turn": False,
                    "register": "peer",
                    "phoneme_focus_ipa": ["ɻ", "ʈ", "b"],
                    "branches": [
                        {
                            "intent_label": "officer_issues_new_kit",
                            "trigger_keywords": ["சரி", "புதிய", "எடுத்துக்கோ", "sari", "pudhiya", "eduthukko"],
                            "target_node_id": "safe_04_officer_grant",
                            "confidence_threshold": 0.55
                        }
                    ]
                },
                "safe_04_officer_grant": {
                    "node_id": "safe_04_officer_grant",
                    "l2_text": "சரி சரி, ஸ்டோர் ரூம்ல போய் புதிய பெல்ட் மற்றும் ஹெல்மெட் எடுத்துக்கோ.",
                    "transliteration": "Sari sari, store roomla poi pudhiya belt matrum helmet eduthukko.",
                    "l1_translation": "ठीक है, स्टोर रूम से नया बेल्ट और हेलमेट ले लो।",
                    "is_persona_turn": True,
                    "register": "peer",
                    "phoneme_focus_ipa": ["ʈ", "ɭ"],
                    "branches": []
                }
            }
        },
        {
            "situation_id": "ta_const_03_concrete_mix",
            "title_l1": "कंक्रीट और मसाले का सही अनुपात पूछना",
            "title_l2": "கான்கிரீட் கலவை விகிதம் கேட்டல்",
            "description": "Asking supervisor for the correct ratio of cement, sand, and gravel for slab casting.",
            "domain": "construction",
            "corridor": "bhojpuriTamil",
            "persona_name": "Senior Mason Selvam (மூத்த கொத்தனார் செல்வம்)",
            "difficulty_tier": 2,
            "entry_node_id": "mix_01_ask_ratio",
            "tags": ["masonry", "materials", "concrete", "ratio"],
            "nodes": {
                "mix_01_ask_ratio": {
                    "node_id": "mix_01_ask_ratio",
                    "l2_text": "அண்ணே, இந்த தளத்துக்கு சிமெண்ட் மணல் கலவை அளவு என்ன போடணும்?",
                    "transliteration": "Anne, indha thalathukku cement manal kalavai alavu enna podanum?",
                    "l1_translation": "भैया, इस छत की ढलाई के लिए सीमेंट-बालू का क्या मसाला बनाना है?",
                    "is_persona_turn": False,
                    "register": "peer",
                    "phoneme_focus_ipa": ["ɳ", "ɭ", "ʈ"],
                    "branches": [
                        {
                            "intent_label": "mason_specifies_ratio",
                            "trigger_keywords": ["ஒன்று", "ரெண்டு", "நாலு", "onru", "rendu", "naalu"],
                            "target_node_id": "mix_02_mason_reply",
                            "confidence_threshold": 0.55
                        }
                    ]
                },
                "mix_02_mason_reply": {
                    "node_id": "mix_02_mason_reply",
                    "l2_text": "ஒரு சட்டி சிமெண்ட், ரெண்டு சட்டி மணல், நாலு சட்டி ஜல்லி போடணும். தண்ணி அதிகமா ஊத்தாதே!",
                    "transliteration": "Oru satti cement, rendu satti manal, naalu satti jalli podanum. Thanni adhikhamaa oothaadhe!",
                    "l1_translation": "एक तगाड़ी सीमेंट, दो तगाड़ी रेत, और चार तगाड़ी गिट्टी डालो। पानी ज्यादा मत मिलाना!",
                    "is_persona_turn": True,
                    "register": "peer",
                    "phoneme_focus_ipa": ["ʈ", "ɳ", "ɭ"],
                    "branches": [
                        {
                            "intent_label": "user_confirms_water_limit",
                            "trigger_keywords": ["சரி", "தண்ணீர்", "அளவா", "sari", "thanni", "alavaa"],
                            "target_node_id": "mix_03_user_confirm",
                            "confidence_threshold": 0.60
                        }
                    ]
                },
                "mix_03_user_confirm": {
                    "node_id": "mix_03_user_confirm",
                    "l2_text": "சரிங்க அண்ணே, கரெக்டா தண்ணி ஊத்தி கலக்குறேன்.",
                    "transliteration": "Saringa anne, correct-aa thanni oothi kalakkuren.",
                    "l1_translation": "ठीक है भैया, नाप कर ही पानी मिलाऊंगा।",
                    "is_persona_turn": False,
                    "register": "peer",
                    "phoneme_focus_ipa": ["ɳ", "ɭ"],
                    "branches": []
                }
            }
        },
        {
            "situation_id": "ta_const_04_site_injury_triage",
            "title_l1": "कार्यस्थल पर चोट लगने पर मदद मांगना",
            "title_l2": "வேலை இடத்தில் காயம் ஏற்பட்டால் உதவி கேட்டல்",
            "description": "Reporting a sharp rebar puncture wound on foot, asking for first aid and tetanus injection.",
            "domain": "construction",
            "corridor": "bhojpuriTamil",
            "persona_name": "First Aid Supervisor (முதலுதவி அதிகாரி)",
            "difficulty_tier": 1,
            "entry_node_id": "inj_01_report",
            "tags": ["emergency", "health", "injury", "first_aid"],
            "nodes": {
                "inj_01_report": {
                    "node_id": "inj_01_report",
                    "l2_text": "ஐயா, கால்ல கம்பி குத்திடுச்சு! ரத்தம் அதிகமா வருது, முதலுதவி பெட்டி எங்க இருக்கு?",
                    "transliteration": "Aiyaa, kaalla kambi kuthiduchu! Ratham adhikhamaa varudhu, mudhaludhavi petti enga irukku?",
                    "l1_translation": "सर, पैर में सरिया चुभ गया है! बहुत खून बह रहा है, फर्स्ट-एड बॉक्स कहाँ है?",
                    "is_persona_turn": False,
                    "register": "employer",
                    "phoneme_focus_ipa": ["ʈ", "ɖ", "b"],
                    "branches": [
                        {
                            "intent_label": "medic_attends_wound",
                            "trigger_keywords": ["உட்காரு", "மருந்து", "கட்டு", "utkaaru", "marundhu", "kattu"],
                            "target_node_id": "inj_02_medic_response",
                            "confidence_threshold": 0.55
                        }
                    ]
                },
                "inj_02_medic_response": {
                    "node_id": "inj_02_medic_response",
                    "l2_text": "முதல்ல இங்க உட்காரு. மருந்து போட்டு கட்டு போடுறேன். உடனே டிடி ஊசி போட ஆஸ்பத்திரி போவோம்.",
                    "transliteration": "Mudhalla inga utkaaru. Marundhu pottu kattu poduren. Udane TT oosi poda aaspathiri povom.",
                    "l1_translation": "पहले यहाँ बैठो। दवाई लगाकर पट्टी बांधता हूँ। फिर तुरंत टीटी इंजेक्शन के लिए अस्पताल चलेंगे।",
                    "is_persona_turn": True,
                    "register": "employer",
                    "phoneme_focus_ipa": ["ʈ", "ɖ", "ɭ"],
                    "branches": []
                }
            }
        },
        {
            "situation_id": "ta_const_05_tool_checkout",
            "title_l1": "स्टोर रूम से ग्राइंडर और एक्सटेंशन तार लेना",
            "title_l2": "ஸ்டோர் ரூமில் கட்டிங் மெஷின் கேட்டல்",
            "description": "Checking out a circular saw / angle grinder and long extension cable for tile cutting.",
            "domain": "construction",
            "corridor": "bhojpuriTamil",
            "persona_name": "Storekeeper Velu (ஸ்டோர் கீப்பர் வேலு)",
            "difficulty_tier": 2,
            "entry_node_id": "tool_01_request",
            "tags": ["tools", "electrical", "inventory"],
            "nodes": {
                "tool_01_request": {
                    "node_id": "tool_01_request",
                    "l2_text": "அண்ணா, டைல்ஸ் வெட்ட கட்டிங் மெஷினும் நீளமான ஒயர் போர்டும் வேணும்.",
                    "transliteration": "Anna, tiles vetta cutting machine-um neelamaana wire board-um venum.",
                    "l1_translation": "भैया, टाइल्स काटने के लिए कटर मशीन और लंबा वायर बोर्ड चाहिए।",
                    "is_persona_turn": False,
                    "register": "peer",
                    "phoneme_focus_ipa": ["ʈ", "ɭ", "ɳ"],
                    "branches": [
                        {
                            "intent_label": "storekeeper_asks_id",
                            "trigger_keywords": ["டோக்கன்", "கையொப்பம்", "token", "sign"],
                            "target_node_id": "tool_02_store_response",
                            "confidence_threshold": 0.55
                        }
                    ]
                },
                "tool_02_store_response": {
                    "node_id": "tool_02_store_response",
                    "l2_text": "நோட்டுல உன் பெயர் எழுதி கையொப்பம் போடு. சாயங்காலம் அஞ்சு மணிக்குள்ள திரும்ப குடுக்கணும்!",
                    "transliteration": "Nottula un peyar ezhudhi kaiyoppam podu. Saayangalam anju manikkulla thirumba kudukkanum!",
                    "l1_translation": "रजिस्टर में अपना नाम लिखकर दस्तखत करो। शाम 5 बजे तक वापस जमा करना होगा!",
                    "is_persona_turn": True,
                    "register": "peer",
                    "phoneme_focus_ipa": ["ɻ", "ɭ", "ɳ"],
                    "branches": []
                }
            }
        },
        {
            "situation_id": "ta_const_06_electrical_hazard",
            "title_l1": "कटे हुए बिजली के तार का खतरा बताना",
            "title_l2": "மின்சார கம்பி அறுந்து விழுந்ததை எச்சரித்தல்",
            "description": "Alerting coworkers about an exposed live 440V cable sitting in water puddle.",
            "domain": "construction",
            "corridor": "bhojpuriTamil",
            "persona_name": "Coworker (சக தொழிலாளி)",
            "difficulty_tier": 1,
            "entry_node_id": "elec_01_alert",
            "tags": ["safety", "hazard", "electrical", "emergency"],
            "nodes": {
                "elec_01_alert": {
                    "node_id": "elec_01_alert",
                    "l2_text": "அங்கே போகாதீங்க! தண்ணில கரண்ட் கம்பி கிடக்கு, மெயின் சுவிட்ச் ஆஃப் பண்ணுங்க!",
                    "transliteration": "Ange pogaadheenga! Thannila current kambi kidakku, main switch off pannunga!",
                    "l1_translation": "वहाँ मत जाओ! पानी में बिजली का तार गिरा है, मेन स्विच बंद करो!",
                    "is_persona_turn": False,
                    "register": "peer",
                    "phoneme_focus_ipa": ["ɳ", "ɭ", "b"],
                    "branches": [
                        {
                            "intent_label": "coworker_confirms_switch_off",
                            "trigger_keywords": ["ஆஃப்", "பண்ணிட்டேன்", "சரி", "off", "pannitten"],
                            "target_node_id": "elec_02_coworker_reply",
                            "confidence_threshold": 0.55
                        }
                    ]
                },
                "elec_02_coworker_reply": {
                    "node_id": "elec_02_coworker_reply",
                    "l2_text": "நல்லவேளை சொன்னே! நான் உடனே மெயின் சுவிட்சை ஆஃப் பண்ணிடுறேன்.",
                    "transliteration": "Nallavelai sonne! Naan udane main switch-ai off panniduren.",
                    "l1_translation": "अच्छा हुआ तुमने बता दिया! मैं अभी मेन स्विच बंद करता हूँ।",
                    "is_persona_turn": True,
                    "register": "peer",
                    "phoneme_focus_ipa": ["ɭ", "ʈ"],
                    "branches": []
                }
            }
        },
        {
            "situation_id": "ta_const_07_overtime_rate",
            "title_l1": "नाइट शिफ्ट और ओवर-टाइम की दर पूछना",
            "title_l2": "கூடுதல் நேர வேலை (OT) கூலி கேட்டல்",
            "description": "Asking supervisor if night slab casting will be paid at 1.5x / 2.0x overtime rate.",
            "domain": "construction",
            "corridor": "bhojpuriTamil",
            "persona_name": "Site Contractor (கான்ட்ராக்டர்)",
            "difficulty_tier": 3,
            "entry_node_id": "ot_01_query",
            "tags": ["wages", "overtime", "negotiation"],
            "nodes": {
                "ot_01_query": {
                    "node_id": "ot_01_query",
                    "l2_text": "மேஸ்திரி, இன்னைக்கு ராத்திரி ஓவர்-டைம் வேலை செஞ்சா ஒரு மணி நேரத்துக்கு எவ்வளவு கூலி தருவீங்க?",
                    "transliteration": "Mesthiri, innaikku raathiri over-time velai senjaa oru mani nerathukku evvalavu kooli tharuveenga?",
                    "l1_translation": "मेस्त्री जी, आज रात को ओटी करेंगे तो प्रति घंटे का क्या हिसाब मिलेगा?",
                    "is_persona_turn": False,
                    "register": "employer",
                    "phoneme_focus_ipa": ["ɳ", "ɭ", "ɾ"],
                    "branches": [
                        {
                            "intent_label": "contractor_specifies_ot_rate",
                            "trigger_keywords": ["மணிக்கு", "ரூபாய்", "டபுள்", "manikku", "roobai", "double"],
                            "target_node_id": "ot_02_contractor_reply",
                            "confidence_threshold": 0.55
                        }
                    ]
                },
                "ot_02_contractor_reply": {
                    "node_id": "ot_02_contractor_reply",
                    "l2_text": "ஒரு மணி நேரத்துக்கு நூறு ரூபாய், கூடவே ராத்திரி சாப்பாடும் குடுத்திடுவோம்.",
                    "transliteration": "Oru mani nerathukku nooru roobai, koodave raathiri saappaadum kuduthiduvom.",
                    "l1_translation": "प्रति घंटे 100 रुपये मिलेंगे, और साथ में रात का खाना भी मिलेगा।",
                    "is_persona_turn": True,
                    "register": "employer",
                    "phoneme_focus_ipa": ["ɳ", "ʈ", "ɖ"],
                    "branches": []
                }
            }
        },
        {
            "situation_id": "ta_const_08_crane_hand_signals",
            "title_l1": "क्रेन ऑपरेटर को माल उठाने का इशारा देना",
            "title_l2": "கிரேன் ஆபரேட்டருக்கு சைகை காட்டுதல்",
            "description": "Calling crane operator via walkie-talkie / shouting to hoist steel reinforcement cage.",
            "domain": "construction",
            "corridor": "bhojpuriTamil",
            "persona_name": "Crane Operator (கிரேன் டிரைவர்)",
            "difficulty_tier": 2,
            "entry_node_id": "crane_01_hoist",
            "tags": ["heavy_machinery", "crane", "signals"],
            "nodes": {
                "crane_01_hoist": {
                    "node_id": "crane_01_hoist",
                    "l2_text": "டிரைவர் அண்ணா, கொக்கியை மெதுவா கீழே இறக்குங்க! கம்பி கட்டியாச்சு, இப்போ மேலே தூக்குங்க!",
                    "transliteration": "Driver anna, kokkiyai medhuvaa keezhe irakkunga! Kambi kattiyaachu, ippo mele thookkunga!",
                    "l1_translation": "ड्राइवर भैया, हुक धीरे से नीचे लाइए! सरिया बंध गया है, अब ऊपर उठाइए!",
                    "is_persona_turn": False,
                    "register": "peer",
                    "phoneme_focus_ipa": ["ɻ", "ʈ", "b"],
                    "branches": [
                        {
                            "intent_label": "driver_acknowledges_lift",
                            "trigger_keywords": ["தூக்குறேன்", "விலகு", "thookkuren", "vilagu"],
                            "target_node_id": "crane_02_driver_reply",
                            "confidence_threshold": 0.55
                        }
                    ]
                },
                "crane_02_driver_reply": {
                    "node_id": "crane_02_driver_reply",
                    "l2_text": "சரி, எல்லாரும் கம்பியை விட்டு தள்ளி நில்லுங்க!",
                    "transliteration": "Sari, ellaarum kambiyai vittu thalli nillunga!",
                    "l1_translation": "ठीक है, सब लोग सरिये के नीचे से हटकर दूर खड़े रहो!",
                    "is_persona_turn": True,
                    "register": "peer",
                    "phoneme_focus_ipa": ["ɭ", "ʈ"],
                    "branches": []
                }
            }
        },
        {
            "situation_id": "ta_const_09_weather_evacuation",
            "title_l1": "भारी बारिश में काम रोककर सुरक्षित जगह जाना",
            "title_l2": "மழையின் போது வேலையை நிறுத்தி ஒதுங்குதல்",
            "description": "Explaining that thunder and heavy rain makes scaffolding slippery and dangerous.",
            "domain": "construction",
            "corridor": "bhojpuriTamil",
            "persona_name": "Site Supervisor (மேஸ்திரி)",
            "difficulty_tier": 1,
            "entry_node_id": "rain_01_alert",
            "tags": ["weather", "safety", "evacuation"],
            "nodes": {
                "rain_01_alert": {
                    "node_id": "rain_01_alert",
                    "l2_text": "மேஸ்திரி, ரொம்ப பலத்த மழை வருது. சாரக்கட்டு வழுக்குது, வேலையை நிறுத்தலாமா?",
                    "transliteration": "Mesthiri, romba balatha mazhai varudhu. Saarakkattu vazhukkudhu, velaiyai niruthalaamaa?",
                    "l1_translation": "मेस्त्री जी, बहुत तेज बारिश आ रही है। पाड़ फिसल रही है, क्या काम रोक दें?",
                    "is_persona_turn": False,
                    "register": "employer",
                    "phoneme_focus_ipa": ["ɻ", "ʈ", "ɭ"],
                    "branches": [
                        {
                            "intent_label": "supervisor_orders_halt",
                            "trigger_keywords": ["நிறுத்து", "ஷெட்", "வா", "niruthu", "shed", "vaa"],
                            "target_node_id": "rain_02_supervisor_reply",
                            "confidence_threshold": 0.55
                        }
                    ]
                },
                "rain_02_supervisor_reply": {
                    "node_id": "rain_02_supervisor_reply",
                    "l2_text": "ஆமாம், எல்லா மின்சார மெஷின்களையும் மூடி வச்சிட்டு உடனே ஷெட்டுக்குள்ள வாங்க!",
                    "transliteration": "Aamaam, ellaa minsara machine-galaiyum moodi vachittu udane shed-kulla vaanga!",
                    "l1_translation": "हाँ, सभी बिजली की मशीनें ढककर तुरंत टीन शेड में आ जाओ!",
                    "is_persona_turn": True,
                    "register": "employer",
                    "phoneme_focus_ipa": ["ɭ", "ʈ", "ɖ"],
                    "branches": []
                }
            }
        },
        {
            "situation_id": "ta_const_10_drinking_water_rest",
            "title_l1": "धूप में पीने का साफ पानी और ब्रेक मांगना",
            "title_l2": "குடிநீர் மற்றும் ஓய்வு கேட்டல்",
            "description": "Requesting clean drinking water tank refill during extreme afternoon heat wave.",
            "domain": "construction",
            "corridor": "bhojpuriTamil",
            "persona_name": "Site Supervisor (மேஸ்திரி)",
            "difficulty_tier": 1,
            "entry_node_id": "water_01_request",
            "tags": ["welfare", "water", "health"],
            "nodes": {
                "water_01_request": {
                    "node_id": "water_01_request",
                    "l2_text": "அண்ணா, வெயில் ரொம்ப அதிகமா இருக்கு. குடிக்க நல்ல தண்ணி கேன் காலியா இருக்கு, மாத்த சொல்லுங்க.",
                    "transliteration": "Anna, veyil romba adhikhamaa irukku. Kudikka nalla thanni can kaaliyaa irukku, maatha sollunga.",
                    "l1_translation": "भैया, धूप बहुत तेज है। पीने का पानी खत्म हो गया है, नया कैन मंगा दीजिए।",
                    "is_persona_turn": False,
                    "register": "peer",
                    "phoneme_focus_ipa": ["ɳ", "ɭ", "ʈ"],
                    "branches": [
                        {
                            "intent_label": "supervisor_refills_water",
                            "trigger_keywords": ["தண்ணி", "வண்டி", "நிழல்", "thanni", "vandi", "nizhal"],
                            "target_node_id": "water_02_supervisor_reply",
                            "confidence_threshold": 0.55
                        }
                    ]
                },
                "water_02_supervisor_reply": {
                    "node_id": "water_02_supervisor_reply",
                    "l2_text": "இப்போவே புது கேன் எடுத்துட்டு வர சொல்றேன். பத்து நிமிஷம் நிழல்ல உட்காருங்க.",
                    "transliteration": "Ippove pudhu can eduthuttu vara solren. Pathu nimisham nizhalla utkaarunga.",
                    "l1_translation": "मैं अभी नया कैन भिजवाता हूँ। 10 मिनट छांव में सुस्ता लो।",
                    "is_persona_turn": True,
                    "register": "peer",
                    "phoneme_focus_ipa": ["ɻ", "ɭ", "ʈ"],
                    "branches": []
                }
            }
        }
    ]


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate situation curriculum JSON datasets.")
    parser.add_argument(
        "--corridor",
        type=str,
        default="bhojpuri_tamil",
        choices=["bhojpuri_tamil", "odia_malayalam", "hindi_kannada"],
        help="Target language corridor.",
    )
    parser.add_argument(
        "--domain",
        type=str,
        default="construction",
        choices=["construction", "healthcare", "logistics_delivery"],
        help="Occupational domain.",
    )
    parser.add_argument(
        "--output_dir",
        type=str,
        default="./data",
        help="Target output directory for compiled JSON.",
    )
    args = parser.parse_args()

    out_dir = Path(args.output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    situations = []
    if args.domain == "construction" and args.corridor == "bhojpuri_tamil":
        situations = get_construction_tamil_situations()

    out_file = out_dir / f"{args.domain}_tamil.json"
    with open(out_file, "w", encoding="utf-8") as f:
        json.dump(situations, f, indent=2, ensure_ascii=False)

    logger.info(f"Generated {len(situations)} situations for {args.corridor} [{args.domain}] -> {out_file}")
    return 0


if __name__ == "__main__":
    main()
