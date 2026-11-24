-- Reference data: the topic taxonomy used by onboarding, community categorisation and (later) topic reputation.
INSERT INTO topics (slug, name, description) VALUES
  ('programming',   'Programming',    'Languages, tools, craft and the work of building software'),
  ('technology',    'Technology',     'Hardware, platforms, the industry and where it is heading'),
  ('security',      'Cybersecurity',  'Defence, offence, privacy and the people who keep systems safe'),
  ('books',         'Books',          'Reading lists, reviews and long conversations about writing'),
  ('career',        'Career',         'Jobs, growth, interviews and navigating work'),
  ('startups',      'Startups',       'Founding, building and failing forward'),
  ('science',       'Science',        'Research, discoveries and how we know what we know'),
  ('movies',        'Movies & TV',    'Film, series, craft and criticism'),
  ('fitness',       'Fitness',        'Training, nutrition and staying well'),
  ('design',        'Design',         'Product, visual and interaction design'),
  ('data',          'Data & AI',      'Data science, machine learning and applied AI'),
  ('gaming',        'Gaming',         'Games, studios and play');
