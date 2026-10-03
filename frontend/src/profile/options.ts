import type { ExperienceLevel, WorkExperience } from '../api'

export const LEVELS: { value: ExperienceLevel; label: string; years: number }[] = [
  { value: 'SCHOOL_STUDENT', label: 'School student', years: 0 },
  { value: 'UNDERGRADUATE', label: 'Undergraduate student', years: 4 },
  { value: 'GRADUATE', label: 'Graduate student', years: 2 },
  { value: 'PROFESSIONAL', label: 'Working professional', years: 0 },
]

export const yearsOfStudy = (level: ExperienceLevel | '') => LEVELS.find((l) => l.value === level)?.years ?? 0

export const ordinalYear = (n: number) => `${n}${['st', 'nd', 'rd'][n - 1] ?? 'th'} year`

export const WORK_EXPERIENCE: { value: WorkExperience; label: string }[] = [
  { value: 'UNDER_ONE_YEAR', label: 'Less than 1 year' },
  { value: 'ONE_TO_TWO_YEARS', label: '1–2 years' },
  { value: 'THREE_TO_FIVE_YEARS', label: '3–5 years' },
  { value: 'SIX_TO_TEN_YEARS', label: '6–10 years' },
  { value: 'OVER_TEN_YEARS', label: '10+ years' },
]

export function describeLevel(level: ExperienceLevel, classYear: number | null, workExperience: WorkExperience | null) {
  const label = LEVELS.find((l) => l.value === level)?.label ?? level
  if (classYear) return `${label} · ${ordinalYear(classYear)}`
  const years = WORK_EXPERIENCE.find((w) => w.value === workExperience)?.label
  return years ? `${label} · ${years}` : label
}

export const LANGUAGES = [
  'Ada', 'Assembly', 'Bash', 'C', 'C#', 'C++', 'Clojure', 'COBOL', 'CoffeeScript', 'Crystal', 'CSS', 'D', 'Dart',
  'Elixir', 'Elm', 'Erlang', 'F#', 'Fortran', 'GDScript', 'Go', 'Groovy', 'Haskell', 'HTML', 'Java', 'JavaScript',
  'Julia', 'Kotlin', 'Lisp', 'Lua', 'MATLAB', 'Nim', 'Objective-C', 'OCaml', 'Pascal', 'Perl', 'PHP', 'PowerShell',
  'Prolog', 'Python', 'R', 'Racket', 'Ruby', 'Rust', 'SAS', 'Scala', 'Scheme', 'Solidity', 'SQL', 'Swift',
  'Tcl', 'TypeScript', 'VHDL', 'Verilog', 'Visual Basic', 'Zig',
]

export const FRAMEWORKS = [
  '.NET', 'Angular', 'Android SDK', 'Ansible', 'Apache Kafka', 'Apache Spark', 'ASP.NET Core', 'Astro', 'AWS',
  'Azure', 'Bootstrap', 'Celery', 'Django', 'Docker', 'Electron', 'Express', 'FastAPI', 'Fastify', 'Flask',
  'Flutter', 'Gatsby', 'Gin', 'GitHub Actions', 'Google Cloud', 'GraphQL', 'gRPC', 'Hibernate', 'Jest', 'Jenkins',
  'JUnit', 'Jetpack Compose', 'jQuery', 'Keras', 'Kubernetes', 'Laravel', 'LangChain', 'Micronaut', 'NestJS',
  'Next.js', 'Node.js', 'Nuxt', 'NumPy', 'OpenCV', 'pandas', 'Phoenix', 'Playwright', 'PyTorch', 'pytest',
  'Quarkus', 'Rails', 'React', 'React Native', 'Redux', 'Remix', 'scikit-learn', 'Selenium', 'Spring Boot',
  'Svelte', 'SwiftUI', 'Tailwind CSS', 'TensorFlow', 'Terraform', 'Unity', 'Unreal Engine', 'Vite', 'Vue',
  'Webpack',
]

export const DATABASES = [
  'Amazon Aurora', 'Apache Cassandra', 'ClickHouse', 'CockroachDB', 'Couchbase', 'CouchDB', 'DuckDB', 'DynamoDB',
  'Elasticsearch', 'Firebase Realtime Database', 'Firestore', 'H2', 'InfluxDB', 'MariaDB', 'Memcached',
  'Microsoft SQL Server', 'MongoDB', 'MySQL', 'Neo4j', 'OpenSearch', 'Oracle Database', 'PlanetScale',
  'PostgreSQL', 'Redis', 'ScyllaDB', 'Snowflake', 'SQLite', 'Supabase', 'TimescaleDB',
]

export const EXPERIENCE_SUGGESTIONS = [
  'REST APIs', 'Frontend development', 'Backend development', 'Database design', 'Testing', 'DevOps / CI/CD',
  'Cloud deployment', 'System design', 'Mobile development', 'Machine learning', 'Data engineering',
  'Security', 'Performance tuning', 'Competitive programming', 'Open source',
]

export const GOAL_TEMPLATES = [
  'Get ready for internships and placement interviews',
  'Write cleaner, production-quality code',
  'Understand how systems behave at scale',
  'Build stronger backend and database fundamentals',
]
