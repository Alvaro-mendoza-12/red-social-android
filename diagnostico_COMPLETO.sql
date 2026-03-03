-- ==========================================
-- DIAGNÓSTICO SUPER COMPLETO
-- ==========================================

-- 1. Ver TODAS las políticas de community_members con detalles completos
SELECT 
    '=== COMMUNITY_MEMBERS POLICIES ===' as info,
    policyname,
    cmd as operation,
    permissive,
    roles,
    qual::text as row_filter,
    with_check::text as insert_check
FROM pg_policies
WHERE tablename = 'community_members' AND schemaname = 'public';

-- 2. Ver TODAS las políticas de group_members
SELECT 
    '=== GROUP_MEMBERS POLICIES ===' as info,
    policyname,
    cmd as operation,
    permissive,
    roles,
    qual::text as row_filter,
    with_check::text as insert_check
FROM pg_policies
WHERE tablename = 'group_members' AND schemaname = 'public';

-- 3. Ver políticas de group_messages
SELECT 
    '=== GROUP_MESSAGES POLICIES ===' as info,
    policyname,
    cmd as operation
FROM pg_policies
WHERE tablename = 'group_messages' AND schemaname = 'public';

-- 4. Ver políticas de community_messages
SELECT 
    '=== COMMUNITY_MESSAGES POLICIES ===' as info,
    policyname,
    cmd as operation
FROM pg_policies
WHERE tablename = 'community_messages' AND schemaname = 'public';

-- 5. Verificar que RLS está habilitado
SELECT 
    '=== RLS STATUS ===' as info,
    tablename,
    rowsecurity as rls_enabled
FROM pg_tables
WHERE tablename IN ('community_members', 'group_members', 'group_messages', 'community_messages')
AND schemaname = 'public';

-- 6. Ver las funciones helper y verificar que son SECURITY DEFINER
SELECT 
    '=== HELPER FUNCTIONS ===' as info,
    proname as function_name,
    prosecdef as is_security_definer,
    provolatile as volatility
FROM pg_proc 
WHERE proname IN ('is_group_member', 'is_group_admin', 'is_community_member', 'is_community_admin')
AND pronamespace = 'public'::regnamespace;

-- 7. Contar cuántas políticas hay en total
SELECT 
    '=== POLICY COUNT ===' as info,
    tablename,
    COUNT(*) as policy_count
FROM pg_policies
WHERE tablename IN ('community_members', 'group_members', 'group_messages', 'community_messages')
AND schemaname = 'public'
GROUP BY tablename;
