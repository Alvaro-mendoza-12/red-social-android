import {textOf,type GenericRow} from '../../core/utils'
export type ProfileIdentity = {
  verified: boolean
  admin: boolean
  publicId: string
}
export function profileIdentity(row: GenericRow): ProfileIdentity {
  const yes = (value: unknown) => value === true || value === 1 || value === 'true'
  return {
    verified: yes(row.is_verified ?? row.isVerified ?? row.verified),
    admin:
      yes(row.is_admin ?? row.isAdmin) ||
      ['admin', 'super_admin', 'owner'].includes(
        textOf(row, ['admin_role', 'adminRole', 'role'], '').toLowerCase(),
      ),
    publicId: textOf(row, ['public_id', 'publicId', 'user_code', 'userCode', 'numeric_id'], ''),
  }
}
