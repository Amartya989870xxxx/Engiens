import { BRAND } from '../brand'

/** The Engiens mark plus wordmark. The image is decorative because the name is right next to it. */
export function Logo({ size = 24, className = '' }: { size?: number; className?: string }) {
  return (
    <span className={`inline-flex items-center gap-2.5 ${className}`}>
      <img src="/engiens-180.png" alt="" width={size} height={size} className="rounded-[22%]" />
      <span className="font-display text-xl text-ink">{BRAND}</span>
    </span>
  )
}
